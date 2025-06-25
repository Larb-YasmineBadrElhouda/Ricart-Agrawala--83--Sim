// Simulation de l’Algorithme de Ricart & Agrawala (1983) avec jeton implicite, pannes, GUI 

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
//---------------------------------------------------------------
/* ---------------------- Message -------------------------------
//---------------------------------------------------------------
/*
Représente un message échangé entre processus (soit REQUEST, soit REPLY)
Chaque message contient : le type (demande ou réponse), l'identifiant de l'expéditeur, et un horodatage logique (timestamp)
*/
class Message implements Serializable {
    public enum Type { REQUEST, REPLY }
    public Type type;
    public int senderId;
    public long timestamp;

    public Message(Type type, int senderId, long timestamp) {
        this.type = type;
        this.senderId = senderId;
        this.timestamp = timestamp;
    }
}

//---------------------------------------------------------------
/* ---------------------- Process -------------------------------
//---------------------------------------------------------------
/*
- Simule un processus pair-à-pair dans l’algorithme de Ricart & Agrawala amélioré (1983)
- Chaque Processus :
   - agit comme client et serveur via des sockets
   - a sa propre horloge logique (Horloge de Lamport)
   - peut tomber en panne ou redémarrer (géré via l’interface)
   - gère les requêtes reçues, les réponses à envoyer, la section critique (SC), et le jeton implicite
   - met à jour son interface graphique locale et ses logs
*/

class Process {
    int id; // identifiant d processus

    int port;  // Port réseau utilisé par ce processus pour recevoir des messages.
    //Chaque processus écoute sur un port 5000 + id pour simplifier.

    Map<Integer, String> peers; //Table des autres processus (paires).
    /* Elle associe l’id de chaque pair à son adresse IP:port.
       Permet d’envoyer des messages aux autres processus via sockets. */ 

    ServerSocket server; // Socket serveur local
    /* pour recevoir les messages entrants (REQUEST ou REPLY).
       C’est la partie "serveur" du modèle pair-à-pair. */

    volatile boolean requestingCS = false;
    /* Indique si ce processus est en train de demander l’entrée en section critique (SC).
       Utilisé pour prendre des décisions lors de la réception de REQUEST. */ 


    volatile boolean inCS = false;
    /* Indique si le processus est actuellement dans la section critique (SC).
       Cela bloque l’envoi de REPLY aux autres tant qu’on n’en sort pas. */


    volatile long clock = 0; 
    /* Horloge logique de Lamport.
       Incrémentée à chaque événement, elle permet d’ordonner les événements dans le système distribué. */


    Set<Integer> repliesPending = ConcurrentHashMap.newKeySet();
    /*Liste des identifiants des processus dont on attend encore une réponse (REPLY).
      Elle est vidée au fur et à mesure qu’on reçoit les REPLY. */


    Queue<Message> deferred = new ConcurrentLinkedQueue<>();
    /*File des requêtes REQUEST différées.
      Quand on ne peut pas répondre immédiatement (car on est en SC ou on a priorité), 
      on garde la requête ici pour répondre plus tard */


    Random rand = new Random();
    GUI gui;
    volatile boolean isAlive = true;
    /* État de santé du processus.
       false = en panne, true = actif. Géré par bouton GUI. 
       Quand en panne, ne répond plus, ne demande plus, et ne traite rien. */ 

    public Process(int id, Map<Integer, String> peers, GUI gui) {
        this.id = id;
        this.port = 5000 + id;
        this.peers = peers;
        this.gui = gui;
    }

    public void start() throws IOException { 
        // start() :démarre les threads de communication et de comportement aléatoire (demande SC, exécution SC).
        server = new ServerSocket(port);

        new Thread(() -> {
            while (true) {
                try {
                    Socket socket = server.accept();
                    if (!isAlive) {
                        socket.close();
                        continue;
                    }
                    ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
                    Message msg = (Message) in.readObject();

                    // handleMessage() : traitement des messages reçus, incluant différé ou réponse immédiate selon l'état
                    handleMessage(msg);

                    socket.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();

        new Thread(() -> {
            while (true) {
                try {
                    if (!isAlive) {
                        updateGUI("Panne");
                        Thread.sleep(1000);
                        continue;
                    }
                    updateGUI("Repos");
                    Thread.sleep(4000 + rand.nextInt(3000));

                    //requestCS() : demande la SC en envoyant des messages REQUEST à tous les autres.
                    requestCS(); 

                    // enterCS() : attend toutes les réponses (REPLY) avant d’entrer en SC.
                    enterCS(); 
                    updateGUI("SC");
                    Thread.sleep(3000 + rand.nextInt(1000));

                    // exitCS() : libère la SC, envoie des REPLY à ceux en attente.
                    exitCS();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    public void setAlive(boolean status) {
        this.isAlive = status;
        if (!status) log("tombe en panne (manuelle)");
        else log("redemarrage manuel");
    }

//---------------------------------------------------------------
//---------------------- requestCS ------------------------------
//---------------------------------------------------------------

    private synchronized void requestCS() {
        clock++;
        requestingCS = true;
        repliesPending.clear();
        for (int peerId : peers.keySet()) {
            if (peerId != id) {
                repliesPending.add(peerId);
                sendMessage(new Message(Message.Type.REQUEST, id, clock), peerId);
            }
        }
        updateGUI("Demande");
    }


//---------------------------------------------------------------
//---------------------- enterCS   ------------------------------
//---------------------------------------------------------------

    private synchronized void enterCS() {
        while (!repliesPending.isEmpty()) {
            try { wait(200); } catch (InterruptedException e) {}
        }
        inCS = true;
        // --->  Ici, le jeton est acquis virtuellement lorsqu'on a reçu tous les REPLY.
        log("entre en section critique - JETON CHEZ MOI");
    }


//---------------------------------------------------------------
//---------------------- exitCS   -------------------------------
//---------------------------------------------------------------

    private synchronized void exitCS() {
        inCS = false;
        requestingCS = false;
        log("sort de section critique");
        updateGUI("Repos");
        while (!deferred.isEmpty()) {
            Message msg = deferred.poll();
            sendMessage(new Message(Message.Type.REPLY, id, clock), msg.senderId);
            // --->  "rend le jeton" implicitement en envoyant des REPLY différés aux processus en attente.
        }
    }

//---------------------------------------------------------------
//---------------------- handleMessage  -------------------------
//---------------------------------------------------------------

    private synchronized void handleMessage(Message msg) {
        clock = Math.max(clock, msg.timestamp) + 1;
        log("reçu " + msg.type + " de P" + msg.senderId + " [T=" + msg.timestamp + "]");
        switch (msg.type) {
            case REQUEST:
                boolean replyNow = !requestingCS ||
                        (msg.timestamp < clock) ||
                        (msg.timestamp == clock && msg.senderId < id);
                if (replyNow && !inCS) {
                    sendMessage(new Message(Message.Type.REPLY, id, clock), msg.senderId);
                } else {
                    deferred.add(msg); // ici :🔴 Si je garde la requête, c’est que j’ai priorité et donc le "jeton" implicite
                    // -> la logique de priorité de Lamport détermine qui "garde le jeton".
                }
                break;
            case REPLY:
                repliesPending.remove(msg.senderId);
                break;
        }
        notifyAll();
    }

    private void sendMessage(Message msg, int destId) {
        new Thread(() -> {
            try {
                String[] addr = peers.get(destId).split(":");
                Socket socket = new Socket(addr[0], Integer.parseInt(addr[1]));
                ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                out.writeObject(msg);
                socket.close();
            } catch (IOException e) {
                log("Erreur envoi vers " + destId + " (peut-etre en panne)");
            }
        }).start();
    }

    private void log(String m) {
        String full = "[T=" + clock + "] " + m;
        System.out.println("P" + id + " " + full);
        try { Thread.sleep(500); } catch (InterruptedException e) {} // ralentir affichage
        gui.appendLog(id, full);
    }

    private void updateGUI(String state) {
        gui.updateState(id, state, inCS);
    }
}

//---------------------------------------------------------------
//---------------------- l'Interface   -------------------------
//---------------------------------------------------------------

/* Gère l'interface graphique de la simulation
 Affiche :
 - L'état de chaque processus (Repos, Demande, SC, Panne)
 - Les logs propres à chaque processus (mis à jour dynamiquement)
 - Les boutons de panne/rétablissement
 - Une disposition en 2 colonnes (5 processus à gauche, 5 à droite) */

class GUI {
    JFrame frame;
    Map<Integer, JLabel> labels = new HashMap<>();
    Map<Integer, JButton> panneButtons = new HashMap<>();
    Map<Integer, JTextArea> logs = new HashMap<>();
    Map<Integer, Process> processes = new HashMap<>();

    public GUI() {
        frame = new JFrame("Simulation Ricart & Agrawala - Jeton Amélioré");
        frame.setSize(900, 800);
        frame.setLayout(new GridLayout(5, 2));
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    }

    public void addProcess(Process p) {
        processes.put(p.id, p);

        JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout());

        JLabel label = new JLabel("Processus " + p.id + " : Repos [T=0]");
        JTextArea logArea = new JTextArea(6, 30);
        logArea.setEditable(false);
        JScrollPane scroll = new JScrollPane(logArea);

        JButton btn = new JButton("Panne / Redémarrer");
        btn.addActionListener(e -> {
            boolean current = p.isAlive;
            p.setAlive(!current);
            updateState(p.id, current ? "Panne" : "Repos", false);
        });

        logs.put(p.id, logArea);
        labels.put(p.id, label);
        panneButtons.put(p.id, btn);

        panel.add(label, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(btn, BorderLayout.SOUTH);
        frame.add(panel);
    }

    public void show() {
        frame.setVisible(true);
    }

    public void updateState(int id, String state, boolean hasToken) {
        SwingUtilities.invokeLater(() -> {
            String suffix = hasToken ? " - JETON CHEZ MOI" : "";
            labels.get(id).setText("Processus " + id + " : " + state + suffix);
        });
    }

    public void appendLog(int id, String log) {
        SwingUtilities.invokeLater(() -> {
            JTextArea area = logs.get(id);
            area.append(log + "\n");
            area.setCaretPosition(area.getDocument().getLength());
        });
    }
}


//---------------------------------------------------------------
//---------------------- classe principale -------------------------
//---------------------------------------------------------------

/* Classe principale (point d'entrée de l'application)
Initialise :
 - les adresses des 10 processus
 - l’interface graphique
 - chaque Processus avec ses paramètres (id, liste de pairs, référence GUI)
 - Lance tous les processus en parallèle */


public class RicartAgrawalaSimulation {
    public static void main(String[] args) throws Exception {
        Map<Integer, String> peers = new HashMap<>();
        for (int i = 1; i <= 10; i++) {
            peers.put(i, "localhost:" + (5000 + i));
        }
        GUI gui = new GUI();
        for (int i = 1; i <= 10; i++) {
            Process p = new Process(i, peers, gui);
            gui.addProcess(p);
            p.start();
        }
        gui.show();
    }
}
