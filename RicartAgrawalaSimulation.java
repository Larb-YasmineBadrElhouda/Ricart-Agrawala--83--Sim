// Simulation de l'Algorithme de Ricart & Agrawala (1983) avec interface moderne

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;

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

class Process {
    int id;
    int port;
    Map<Integer, String> peers;
    ServerSocket server;
    volatile boolean requestingCS = false;
    volatile boolean inCS = false;
    volatile long clock = 0;
    Set<Integer> repliesPending = ConcurrentHashMap.newKeySet();
    Queue<Message> deferred = new ConcurrentLinkedQueue<>();
    Random rand = new Random();
    GUI gui;
    volatile boolean isAlive = true;

    public Process(int id, Map<Integer, String> peers, GUI gui) {
        this.id = id;
        this.port = 5000 + id;
        this.peers = peers;
        this.gui = gui;
    }

    public void start() throws IOException {
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

                    requestCS();
                    enterCS();
                    updateGUI("SC");
                    Thread.sleep(3000 + rand.nextInt(1000));
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
        else log("redémarrage manuel");
    }

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

    private synchronized void enterCS() {
        while (!repliesPending.isEmpty()) {
            try { wait(200); } catch (InterruptedException e) {}
        }
        inCS = true;
        log("entre en section critique - JETON CHEZ MOI");
        gui.announceToken(id);
    }

    private synchronized void exitCS() {
        inCS = false;
        requestingCS = false;
        log("sort de section critique");
        updateGUI("Repos");
        while (!deferred.isEmpty()) {
            Message msg = deferred.poll();
            sendMessage(new Message(Message.Type.REPLY, id, clock), msg.senderId);
        }
    }

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
                    deferred.add(msg);
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
                log("Erreur envoi vers " + destId + " (peut-être en panne)");
            }
        }).start();
    }

    private void log(String m) {
        String full = "[T=" + clock + "] " + m;
        System.out.println("P" + id + " " + full);
        try { Thread.sleep(500); } catch (InterruptedException e) {}
        gui.appendLog(id, full);
    }

    private void updateGUI(String state) {
        gui.updateState(id, state, inCS);
    }
}

// Interface d'accueil moderne
class MenuInterface extends JFrame {
    private JPanel mainPanel;
    private JButton startButton;
    private JButton exitButton;
    private JLabel loadingLabel;
    private Timer animationTimer;
    private int animationStep = 0;
    
    public MenuInterface() {
        setTitle("Simulation Algorithme Ricart & Agrawala");
        initializeInterface();
        setupComponents();
        setupLayout();
        setupEventListeners();
    }
    
    private void initializeInterface() {
        setSize(800, 600);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);
    }
    
    private void setupComponents() {
        mainPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                // Gradient de fond
                GradientPaint gradient = new GradientPaint(
                    0, 0, new Color(25, 25, 112),
                    getWidth(), getHeight(), new Color(138, 43, 226)
                );
                g2d.setPaint(gradient);
                g2d.fillRect(0, 0, getWidth(), getHeight());
                
                // Effets d'étoiles
                g2d.setColor(Color.WHITE);
                Random rand = new Random(42);
                for (int i = 0; i < 50; i++) {
                    int x = rand.nextInt(getWidth());
                    int y = rand.nextInt(getHeight());
                    int size = rand.nextInt(3) + 1;
                    g2d.fillOval(x, y, size, size);
                }
            }
        };
        mainPanel.setLayout(null);
        
        // Titre principal
        JLabel titleLabel = new JLabel("SIMULATEUR D'ALGORITHMES");
        titleLabel.setFont(new Font("Arial", Font.BOLD, 32));
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
        titleLabel.setBounds(50, 50, 700, 50);
        
        // Sous-titre
        JLabel subtitleLabel = new JLabel("Algorithme de Ricart & Agrawala (1983)");
        subtitleLabel.setFont(new Font("Arial", Font.ITALIC, 18));
        subtitleLabel.setForeground(new Color(255, 215, 0));
        subtitleLabel.setHorizontalAlignment(SwingConstants.CENTER);
        subtitleLabel.setBounds(50, 100, 700, 30);
        
        // Carte de présentation
        JPanel cardPanel = createInfoCard();
        cardPanel.setBounds(150, 150, 500, 200);
        
        // Bouton démarrer
        startButton = createStyledButton("DEMARRER LA SIMULATION", new Color(50, 205, 50));
        startButton.setBounds(250, 380, 300, 50);
        
        // Bouton quitter
        exitButton = createStyledButton("X QUITTER", new Color(220, 20, 60));
        exitButton.setBounds(250, 450, 300, 50);
        
        // Label de chargement (initialement caché)
        loadingLabel = new JLabel("Demarrage de la simulation...", SwingConstants.CENTER);
        loadingLabel.setFont(new Font("Arial", Font.BOLD, 16));
        loadingLabel.setForeground(Color.YELLOW);
        loadingLabel.setBounds(200, 520, 400, 30);
        loadingLabel.setVisible(false);
        
        // Ajout des composants
        mainPanel.add(titleLabel);
        mainPanel.add(subtitleLabel);
        mainPanel.add(cardPanel);
        mainPanel.add(startButton);
        mainPanel.add(exitButton);
        mainPanel.add(loadingLabel);
        
        add(mainPanel);
    }
    
    private JPanel createInfoCard() {
        JPanel card = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                // Fond de carte avec transparence
                g2d.setColor(new Color(255, 255, 255, 200));
                g2d.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
                
                // Bordure
                g2d.setColor(new Color(100, 100, 100, 150));
                g2d.setStroke(new BasicStroke(2));
                g2d.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 20, 20);
            }
        };
        card.setLayout(new BorderLayout());
        card.setOpaque(false);
        
        JLabel cardTitle = new JLabel("A PROPOS DE L'ALGORITHME", SwingConstants.CENTER);
        cardTitle.setFont(new Font("Arial", Font.BOLD, 16));
        cardTitle.setForeground(new Color(139, 0, 139));
        cardTitle.setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 0));
        
        JTextArea description = new JTextArea(
            "- Algorithme d'exclusion mutuelle distribue (1983)\n" +
            "- Base sur les horloges logiques de Lamport\n" +
            "- Utilise un systeme de jeton implicite\n" +
            "- Simulation avec 10 processus concurrents\n" +
            "- Gestion des pannes et redemarrages\n" +
            "- Interface temps reel avec logs detailles"
        );
        description.setFont(new Font("Arial", Font.PLAIN, 16));
        description.setForeground(Color.BLACK);
        description.setOpaque(false);
        description.setEditable(false);
        description.setBorder(BorderFactory.createEmptyBorder(0, 20, 10, 20));
        
        card.add(cardTitle, BorderLayout.NORTH);
        card.add(description, BorderLayout.CENTER);
        
        return card;
    }
    
    private JButton createStyledButton(String text, Color baseColor) {
        JButton button = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                // Gradient du bouton
                Color lighter = baseColor.brighter();
                Color darker = baseColor.darker();
                
                if (getModel().isPressed()) {
                    GradientPaint gradient = new GradientPaint(0, 0, darker, 0, getHeight(), lighter);
                    g2d.setPaint(gradient);
                } else if (getModel().isRollover()) {
                    GradientPaint gradient = new GradientPaint(0, 0, lighter, 0, getHeight(), baseColor);
                    g2d.setPaint(gradient);
                } else {
                    GradientPaint gradient = new GradientPaint(0, 0, baseColor, 0, getHeight(), darker);
                    g2d.setPaint(gradient);
                }
                
                g2d.fillRoundRect(0, 0, getWidth(), getHeight(), 15, 15);
                
                // Bordure
                g2d.setColor(darker);
                g2d.setStroke(new BasicStroke(2));
                g2d.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 15, 15);
                
                // Texte
                g2d.setColor(Color.WHITE);
                g2d.setFont(getFont());
                FontMetrics fm = g2d.getFontMetrics();
                int textX = (getWidth() - fm.stringWidth(getText())) / 2;
                int textY = (getHeight() + fm.getAscent()) / 2 - 2;
                g2d.drawString(getText(), textX, textY);
            }
        };
        
        button.setFont(new Font("Arial", Font.BOLD, 14));
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        
        return button;
    }
    
    private void setupLayout() {
        setLayout(new BorderLayout());
        add(mainPanel, BorderLayout.CENTER);
    }
    
    private void setupEventListeners() {
        startButton.addActionListener(e -> startSimulation());
        
        exitButton.addActionListener(e -> {
            int result = JOptionPane.showConfirmDialog(
                this,
                "Etes-vous sur de vouloir quitter l'application ?",
                "Confirmation de sortie",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
            );
            
            if (result == JOptionPane.YES_OPTION) {
                System.exit(0);
            }
        });
    }
    
    private void startSimulation() {
        startButton.setEnabled(false);
        exitButton.setEnabled(false);
        loadingLabel.setVisible(true);
        
        // Animation de chargement
        animationTimer = new Timer(200, e -> {
            String[] animations = {"[.]", "[..]", "[...]", "[....]"};
            loadingLabel.setText(animations[animationStep % animations.length] + " Demarrage de la simulation...");
            animationStep++;
        });
        animationTimer.start();
        
        // Démarrage différé pour l'effet visuel
        Timer startTimer = new Timer(2000, e -> {
            animationTimer.stop();
            setVisible(false);
            launchMainSimulation();
        });
        startTimer.setRepeats(false);
        startTimer.start();
    }
    
    private void launchMainSimulation() {
        SwingUtilities.invokeLater(() -> {
            Map<Integer, String> peers = new HashMap<>();
            for (int i = 1; i <= 10; i++) {
                peers.put(i, "localhost:" + (5000 + i));
            }
            GUI gui = new GUI();
            for (int i = 1; i <= 10; i++) {
                Process p = new Process(i, peers, gui);
                gui.addProcess(p);
            }
            gui.show();
            dispose(); // Ferme l'interface d'accueil
        });
    }
}

// Panneau personnalisé pour chaque processus avec design moderne
class ProcessPanel extends JPanel {
    private int processId;
    private String currentState = "Repos";
    private boolean hasToken = false;
    private boolean isDown = false;
    private Timer pulseTimer;
    private float pulseOpacity = 1.0f;
    private boolean pulseIncreasing = false;
    private Color stateColor = new Color(70, 130, 180); // Steel Blue par défaut
    
    public ProcessPanel(int id) {
        this.processId = id;
        setOpaque(false);
        setPreferredSize(new Dimension(450, 200));
        
        // Timer pour l'animation de pulsation quand le processus a le jeton
        pulseTimer = new Timer(100, e -> {
            if (hasToken) {
                if (pulseIncreasing) {
                    pulseOpacity += 0.1f;
                    if (pulseOpacity >= 1.0f) {
                        pulseOpacity = 1.0f;
                        pulseIncreasing = false;
                    }
                } else {
                    pulseOpacity -= 0.1f;
                    if (pulseOpacity <= 0.3f) {
                        pulseOpacity = 0.3f;
                        pulseIncreasing = true;
                    }
                }
                repaint();
            }
        });
    }
    
    public void updateState(String state, boolean token, boolean alive) {
        this.currentState = state;
        this.hasToken = token;
        this.isDown = !alive;
        
        // Mise à jour des couleurs selon l'état
        switch (state) {
            case "Repos":
                stateColor = alive ? new Color(70, 130, 180) : new Color(128, 128, 128); // Steel Blue / Gray
                break;
            case "Demande":
                stateColor = new Color(255, 165, 0); // Orange
                break;
            case "SC":
                stateColor = new Color(50, 205, 50); // Lime Green
                break;
            case "Panne":
                stateColor = new Color(220, 20, 60); // Crimson
                break;
        }
        
        // Animation pour le jeton
        if (token && !pulseTimer.isRunning()) {
            pulseTimer.start();
        } else if (!token && pulseTimer.isRunning()) {
            pulseTimer.stop();
            pulseOpacity = 1.0f;
        }
        
        repaint();
    }
    
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        int width = getWidth();
        int height = getHeight();
        int headerHeight = 60;
        
        // Fond principal avec gradient
        Color lightColor = stateColor.brighter();
        Color darkColor = stateColor.darker();
        
        if (hasToken) {
            // Effet de brillance pour le jeton
            Color glowColor = new Color(255, 215, 0, (int)(pulseOpacity * 100)); // Gold avec transparence
            g2d.setColor(glowColor);
            g2d.fillRoundRect(-5, -5, width + 10, height + 10, 20, 20);
        }
        
        GradientPaint gradient = new GradientPaint(0, 0, lightColor, 0, height, darkColor);
        g2d.setPaint(gradient);
        g2d.fillRoundRect(0, 0, width, height, 15, 15);
        
        // Bordure avec effet d'ombre
        g2d.setColor(new Color(0, 0, 0, 30));
        g2d.fillRoundRect(3, 3, width, height, 15, 15);
        g2d.setColor(stateColor.darker());
        g2d.setStroke(new BasicStroke(2));
        g2d.drawRoundRect(0, 0, width - 1, height - 1, 15, 15);
        
        // En-tête du processus
        g2d.setColor(new Color(255, 255, 255, 200));
        g2d.fillRoundRect(5, 5, width - 10, headerHeight, 10, 10);
        
        // Icône du processus (cercle coloré)
        g2d.setColor(stateColor);
        g2d.fillOval(15, 15, 30, 30);
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("Arial", Font.BOLD, 16));
        g2d.drawString("P" + processId, 23, 35);
        
        // Titre du processus
        g2d.setColor(Color.BLACK);
        g2d.setFont(new Font("Arial", Font.BOLD, 18));
        g2d.drawString("PROCESSUS " + processId, 60, 30);
        
        // État actuel
        g2d.setFont(new Font("Arial", Font.PLAIN, 14));
        String stateText = "État: " + currentState;
        if (hasToken) stateText += " 🔑 JETON";
        g2d.drawString(stateText, 60, 50);
        
        // Indicateur visuel de l'état
        int indicatorY = headerHeight + 15;
        g2d.setColor(new Color(255, 255, 255, 150));
        g2d.fillRoundRect(10, indicatorY, width - 20, 25, 8, 8);
        
        // Barre de progression/état
        g2d.setColor(stateColor);
        int barWidth = (width - 30);
        if (currentState.equals("Demande")) {
            // Animation de chargement pour l'état "Demande"
            long time = System.currentTimeMillis();
            int animatedWidth = (int)((Math.sin(time * 0.01) + 1) * 0.5 * barWidth);
            g2d.fillRoundRect(15, indicatorY + 3, animatedWidth, 19, 6, 6);
        } else {
            g2d.fillRoundRect(15, indicatorY + 3, barWidth, 19, 6, 6);
        }
        
        // Texte de l'état dans la barre
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("Arial", Font.BOLD, 12));
        FontMetrics fm = g2d.getFontMetrics();
        String statusText = currentState.toUpperCase();
        if (isDown) statusText = "HORS SERVICE";
        int textX = (width - fm.stringWidth(statusText)) / 2;
        g2d.drawString(statusText, textX, indicatorY + 17);
        
        // Effets spéciaux selon l'état
        if (hasToken) {
            // Particules dorées pour le jeton
            g2d.setColor(new Color(255, 215, 0, (int)(pulseOpacity * 150)));
            Random rand = new Random(processId * 1000 + System.currentTimeMillis() / 200);
            for (int i = 0; i < 8; i++) {
                int x = rand.nextInt(width - 20) + 10;
                int y = rand.nextInt(height - 100) + headerHeight + 50;
                g2d.fillOval(x, y, 4, 4);
            }
        }
        
        if (currentState.equals("Panne")) {
            // Effet de "cassé" pour les pannes
            g2d.setColor(new Color(255, 0, 0, 100));
            g2d.setStroke(new BasicStroke(3));
            g2d.drawLine(10, 10, width - 10, height - 10);
            g2d.drawLine(width - 10, 10, 10, height - 10);
        }
    }
}

class GUI {
    JFrame frame;
    Map<Integer, ProcessPanel> processPanels = new HashMap<>();
    Map<Integer, JButton> panneButtons = new HashMap<>();
    Map<Integer, JTextArea> logs = new HashMap<>();
    Map<Integer, Process> processes = new HashMap<>();
    JButton startButton;
    boolean started = false;

    public GUI() {
        frame = new JFrame("🚀 Simulation Ricart & Agrawala - Interface Moderne");
        frame.setSize(1200, 900);
        frame.setLayout(new BorderLayout());
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        
        setupHeader();
        setupMainPanel();
    }
    
    private void setupHeader() {
        JPanel headerPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                // Gradient de fond pour l'en-tête
                GradientPaint gradient = new GradientPaint(
                    0, 0, new Color(72, 61, 139),
                    getWidth(), getHeight(), new Color(123, 104, 238)
                );
                g2d.setPaint(gradient);
                g2d.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        headerPanel.setLayout(new BorderLayout());
        headerPanel.setPreferredSize(new Dimension(0, 80));
        
        JLabel title = new JLabel(" SIMULATION RICART & AGRAWALA", JLabel.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 24));
        title.setForeground(Color.WHITE);
        
        startButton = new JButton(" DÉMARRER LA SIMULATION") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                Color baseColor = new Color(50, 205, 50);
                Color lighter = baseColor.brighter();
                Color darker = baseColor.darker();
                
                if (getModel().isPressed()) {
                    GradientPaint gradient = new GradientPaint(0, 0, darker, 0, getHeight(), lighter);
                    g2d.setPaint(gradient);
                } else if (getModel().isRollover()) {
                    GradientPaint gradient = new GradientPaint(0, 0, lighter, 0, getHeight(), baseColor);
                    g2d.setPaint(gradient);
                } else {
                    GradientPaint gradient = new GradientPaint(0, 0, baseColor, 0, getHeight(), darker);
                    g2d.setPaint(gradient);
                }
                
                g2d.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                
                // Texte
                g2d.setColor(Color.WHITE);
                g2d.setFont(getFont());
                FontMetrics fm = g2d.getFontMetrics();
                int textX = (getWidth() - fm.stringWidth(getText())) / 2;
                int textY = (getHeight() + fm.getAscent()) / 2 - 2;
                g2d.drawString(getText(), textX, textY);
            }
        };
        startButton.setFont(new Font("Arial", Font.BOLD, 14));
        startButton.setFocusPainted(false);
        startButton.setBorderPainted(false);
        startButton.setContentAreaFilled(false);
        startButton.setPreferredSize(new Dimension(250, 40));
        startButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        
        headerPanel.add(title, BorderLayout.CENTER);
        
        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.setOpaque(false);
        buttonPanel.add(startButton);
        headerPanel.add(buttonPanel, BorderLayout.SOUTH);
        
        frame.add(headerPanel, BorderLayout.NORTH);
    }
    
    private void setupMainPanel() {
        JPanel mainPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                // Fond dégradé subtil
                GradientPaint gradient = new GradientPaint(
                    0, 0, new Color(240, 248, 255),
                    getWidth(), getHeight(), new Color(230, 230, 250)
                );
                g2d.setPaint(gradient);
                g2d.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        mainPanel.setLayout(new GridLayout(5, 2, 15, 15));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        
        JScrollPane scrollPane = new JScrollPane(mainPanel);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.setOpaque(false);
        
        frame.add(scrollPane, BorderLayout.CENTER);
    }

    public void addProcess(Process p) {
        processes.put(p.id, p);

        JPanel containerPanel = new JPanel(new BorderLayout(10, 10));
        containerPanel.setOpaque(false);
        
        // Panel principal du processus avec design moderne
        ProcessPanel processPanel = new ProcessPanel(p.id);
        processPanels.put(p.id, processPanel);
        
        // Zone de logs avec style moderne
        JTextArea logArea = new JTextArea(8, 35);
        logArea.setFont(new Font("Consolas", Font.PLAIN, 11));
        logArea.setEditable(false);
        logArea.setBackground(new Color(248, 248, 255));
        logArea.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        
        JScrollPane logScroll = new JScrollPane(logArea) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                // Fond avec bordure arrondie
                g2d.setColor(new Color(245, 245, 245));
                g2d.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                
                // Bordure
                g2d.setColor(new Color(200, 200, 200));
                g2d.setStroke(new BasicStroke(1));
                g2d.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 10, 10);
            }
        };
        logScroll.setOpaque(false);
        logScroll.getViewport().setOpaque(false);
        logScroll.setBorder(null);
        logScroll.setPreferredSize(new Dimension(0, 120));
        
        logs.put(p.id, logArea);
        
        // Bouton de contrôle avec style moderne
        JButton controlBtn = new JButton(" CONTRÔLE") {
            private boolean isDown = false;
            
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                Color baseColor = isDown ? new Color(220, 20, 60) : new Color(70, 130, 180);
                Color lighter = baseColor.brighter();
                Color darker = baseColor.darker();
                
                if (getModel().isPressed()) {
                    GradientPaint gradient = new GradientPaint(0, 0, darker, 0, getHeight(), lighter);
                    g2d.setPaint(gradient);
                } else if (getModel().isRollover()) {
                    GradientPaint gradient = new GradientPaint(0, 0, lighter, 0, getHeight(), baseColor);
                    g2d.setPaint(gradient);
                } else {
                    GradientPaint gradient = new GradientPaint(0, 0, baseColor, 0, getHeight(), darker);
                    g2d.setPaint(gradient);
                }
                
                g2d.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                
                // Ombre
                g2d.setColor(new Color(0, 0, 0, 50));
                g2d.fillRoundRect(2, 2, getWidth(), getHeight(), 8, 8);
                
                // Texte
                g2d.setColor(Color.WHITE);
                g2d.setFont(getFont());
                FontMetrics fm = g2d.getFontMetrics();
                String text = isDown ? " REDÉMARRER" : " PANNE";
                int textX = (getWidth() - fm.stringWidth(text)) / 2;
                int textY = (getHeight() + fm.getAscent()) / 2 - 2;
                g2d.drawString(text, textX, textY);
            }
            
            private void updateState() {
                isDown = !p.isAlive;
                repaint();
            }
        };
        
        controlBtn.setFont(new Font("Arial", Font.BOLD, 11));
        controlBtn.setFocusPainted(false);
        controlBtn.setBorderPainted(false);
        controlBtn.setContentAreaFilled(false);
        controlBtn.setPreferredSize(new Dimension(120, 35));
        controlBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        
        controlBtn.addActionListener(e -> {
            boolean current = p.isAlive;
            p.setAlive(!current);
            updateState(p.id, current ? "Panne" : "Repos", false);
            controlBtn.repaint();
        });
        
        panneButtons.put(p.id, controlBtn);
        
        // Panel pour le bouton
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        buttonPanel.setOpaque(false);
        buttonPanel.add(controlBtn);
        
        // Assemblage du container
        containerPanel.add(processPanel, BorderLayout.CENTER);
        containerPanel.add(logScroll, BorderLayout.SOUTH);
        containerPanel.add(buttonPanel, BorderLayout.EAST);
        
        // Configuration du bouton de démarrage
        if (!started) {
            startButton.addActionListener(e -> {
                if (!started) {
                    processes.values().forEach(pr -> {
                        try { 
                            pr.start(); 
                        } catch (IOException ex) { 
                            ex.printStackTrace(); 
                        }
                    });
                    startButton.setText(" SIMULATION EN COURS");
                    startButton.setEnabled(false);
                    started = true;
                }
            });
        }

        // Ajout au panel principal
        JScrollPane scrollPane = (JScrollPane) frame.getContentPane().getComponent(1);
        JPanel mainPanel = (JPanel) scrollPane.getViewport().getView();
        mainPanel.add(containerPanel);
    }

    public void show() {
        frame.setVisible(true);
    }

    public void updateState(int id, String state, boolean hasToken) {
        SwingUtilities.invokeLater(() -> {
            ProcessPanel panel = processPanels.get(id);
            if (panel != null) {
                Process process = processes.get(id);
                panel.updateState(state, hasToken, process.isAlive);
            }
        });
    }

    public void appendLog(int id, String log) {
        SwingUtilities.invokeLater(() -> {
            JTextArea area = logs.get(id);
            if (area != null) {
                // Formatage coloré du log (simulation avec du texte)
                String formattedLog = log;
                if (log.contains("JETON CHEZ MOI")) {
                    formattedLog = "[!!!! JETON] " + log;
                } else if (log.contains("REQUEST")) {
                    formattedLog = "[ REQ] " + log;
                } else if (log.contains("REPLY")) {
                    formattedLog = "[ REP] " + log;
                } else if (log.contains("panne")) {
                    formattedLog = "[ PANNE] " + log;
                } else if (log.contains("redémarrage")) {
                    formattedLog = "[ RESTART] " + log;
                }
                
                area.append(formattedLog + "\n");
                area.setCaretPosition(area.getDocument().getLength());
                
                // Limiter le nombre de lignes pour éviter la surcharge
                int lineCount = area.getLineCount();
                if (lineCount > 50) {
                    try {
                        int excess = lineCount - 50;
                        int endPos = area.getLineEndOffset(excess - 1);
                        area.replaceRange("", 0, endPos);
                    } catch (Exception ex) {
                        // Ignore les erreurs de formatage
                    }
                }
            }
        });
    }

    public void announceToken(int id) {
        SwingUtilities.invokeLater(() -> {
            // Animation spéciale pour l'annonce du jeton
            ProcessPanel panel = processPanels.get(id);
            if (panel != null) {
                // Créer un effet visuel temporaire
                Timer flashTimer = new Timer(200, null);
                final int[] flashCount = {0};
                
                flashTimer.addActionListener(e -> {
                    if (flashCount[0] < 6) {
                        panel.setVisible(flashCount[0] % 2 == 0);
                        flashCount[0]++;
                    } else {
                        panel.setVisible(true);
                        flashTimer.stop();
                    }
                });
                flashTimer.start();
            }
            
            appendLog(id, " >>> PROCESSUS " + id + " DÉTIENT LE JETON EXCLUSIF <<<");
        });
    }
}

public class RicartAgrawalaSimulation {
    public static void main(String[] args) {
        // Configuration Look & Feel pour une meilleure apparence
        /*try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeel());
        } catch (Exception e) {
            // Utiliser le look par défaut si erreur
        }*/
        
        SwingUtilities.invokeLater(() -> {
            new MenuInterface().setVisible(true);
        });
    }
}