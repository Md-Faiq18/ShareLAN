package com.lanshare;

import com.lanshare.model.Transfer;
import com.lanshare.network.FileClient;
import com.lanshare.network.LanDiscovery;
import com.lanshare.network.FileServer;
import com.lanshare.storage.CsvTransferRepository;
import com.lanshare.util.FileValidator;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.DefaultListModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Modern, compact Swing interface for the LAN file sharing services. */
public class Main extends JFrame {
    private static final Color INK = new Color(28, 39, 62);
    private static final Color MUTED = new Color(112, 124, 148);
    private static final Color BACKGROUND = new Color(245, 247, 251);
    private static final Color BLUE = new Color(72, 102, 230);
    private static final Color GREEN = new Color(34, 166, 120);

    private final CardLayout pageLayout = new CardLayout();
    private final JPanel pages = new JPanel(pageLayout);
    private final JLabel pageTitle = new JLabel("Overview");
    private final JLabel status = new JLabel("Receiver stopped");
    private final JLabel receiverPageStatus = new JLabel("Receiver stopped");
    private final JLabel receiverOverview = new JLabel("Start receiving to accept files");
    private final JLabel clientCount = new JLabel("0");
    private final JLabel fileTotal = new JLabel("0");
    private final JLabel byteTotal = new JLabel("0 B");
    private final JLabel recentTransfers = new JLabel("No transfers yet");
    private final JLabel downloadPath = new JLabel();
    private final JLabel transferMessage = new JLabel("Choose a file to get started");
    private final JProgressBar transferProgress = new JProgressBar(0, 100);
    private final JLabel receiveMessage = new JLabel("Waiting for an incoming file");
    private final JProgressBar receiveProgress = new JProgressBar(0, 100);
    private volatile long receiveStartedAt;
    private final JTextField host = new JTextField("127.0.0.1");
    private final JTextField port = new JTextField("5050");
    private final DefaultListModel<LanDiscovery.Device> deviceModel = new DefaultListModel<>();
    private final JList<LanDiscovery.Device> deviceList = new JList<>(deviceModel);
    private final JTextArea activity = new JTextArea();
    private final DefaultTableModel historyRows = new DefaultTableModel(
            new Object[]{"File", "Sender", "Size", "Received"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final Path historyFile = Path.of("data", "history.csv");
    private final CsvTransferRepository repository = new CsvTransferRepository(historyFile);
    private final ExecutorService background = Executors.newFixedThreadPool(2);
    private Path downloads = Path.of("downloads");
    private volatile FileServer server;
    private volatile LanDiscovery lanDiscovery;
    private volatile FileClient.Connection outgoingConnection;

    public Main() {
        super("ShareLAN");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(960, 650));
        setSize(1120, 760);
        setLocationRelativeTo(null);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                stopPeerServices();
                background.shutdownNow();
            }
        });
        getContentPane().setBackground(BACKGROUND);
        setLayout(new BorderLayout());
        add(createSidebar(), BorderLayout.WEST);
        add(createWorkspace(), BorderLayout.CENTER);
        refreshHistory();
    }

    private JPanel createSidebar() {
        JPanel sidebar = new JPanel();
        sidebar.setBackground(new Color(24, 34, 57));
        sidebar.setPreferredSize(new Dimension(218, 0));
        sidebar.setBorder(new EmptyBorder(26, 16, 20, 16));
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));

        JLabel brand = new JLabel("◈  ShareLAN");
        brand.setForeground(Color.WHITE);
        brand.setFont(new Font("Segoe UI", Font.BOLD, 22));
        brand.setBorder(new EmptyBorder(2, 10, 34, 0));
        sidebar.add(brand);
        sidebar.add(navButton("⌂", "Overview", "overview"));
        sidebar.add(navButton("⇧", "Send files", "send"));
        sidebar.add(navButton("⇩", "Receive files", "receive"));
        sidebar.add(navButton("◷", "Transfer history", "history"));
        sidebar.add(Box.createVerticalStrut(22));
        JLabel tools = new JLabel("  TOOLS");
        tools.setForeground(new Color(135, 149, 178));
        tools.setFont(new Font("Segoe UI", Font.BOLD, 10));
        tools.setBorder(new EmptyBorder(8, 0, 8, 0));
        sidebar.add(tools);
        sidebar.add(navButton("⚙", "Settings", "settings"));
        sidebar.add(Box.createVerticalGlue());
        JLabel local = new JLabel("●  LOCAL NETWORK");
        local.setForeground(new Color(128, 204, 178));
        local.setFont(new Font("Segoe UI", Font.BOLD, 10));
        local.setBorder(new EmptyBorder(12, 10, 4, 0));
        sidebar.add(local);
        return sidebar;
    }

    private JButton navButton(String icon, String label, String page) {
        JButton button = new JButton(icon + "    " + label);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setForeground(new Color(220, 227, 241));
        button.setBackground(new Color(24, 34, 57));
        button.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        button.setBorder(new EmptyBorder(12, 12, 12, 8));
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        button.addActionListener(e -> showPage(page, label));
        return button;
    }

    private JPanel createWorkspace() {
        JPanel workspace = new JPanel(new BorderLayout());
        workspace.setOpaque(false);
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(25, 32, 18, 34));
        pageTitle.setFont(new Font("Segoe UI", Font.BOLD, 23));
        pageTitle.setForeground(INK);
        JLabel subtitle = new JLabel("Send and receive directly over the same Wi-Fi network");
        subtitle.setForeground(MUTED);
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.add(pageTitle);
        titles.add(Box.createVerticalStrut(4));
        titles.add(subtitle);
        header.add(titles, BorderLayout.WEST);
        JLabel badge = new JLabel("  ●  SAME WI-FI  ");
        badge.setOpaque(true);
        badge.setBackground(new Color(228, 246, 239));
        badge.setForeground(new Color(34, 132, 99));
        badge.setFont(new Font("Segoe UI", Font.BOLD, 10));
        badge.setBorder(new EmptyBorder(8, 4, 8, 4));
        header.add(badge, BorderLayout.EAST);

        pages.setOpaque(false);
        pages.setBorder(new EmptyBorder(0, 30, 28, 30));
        pages.add(dashboardPage(), "overview");
        pages.add(sendPage(), "send");
        pages.add(receivePage(), "receive");
        pages.add(historyPage(), "history");
        pages.add(settingsPage(), "settings");
        workspace.add(header, BorderLayout.NORTH);
        JScrollPane pageScroll = new JScrollPane(pages,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        pageScroll.setBorder(BorderFactory.createEmptyBorder());
        pageScroll.setOpaque(false);
        pageScroll.getViewport().setOpaque(false);
        workspace.add(pageScroll, BorderLayout.CENTER);
        return workspace;
    }

    private JPanel dashboardPage() {
        JPanel page = new JPanel(new BorderLayout(0, 18));
        page.setOpaque(false);
        JPanel actions = new JPanel(new GridLayout(1, 2, 16, 0));
        actions.setOpaque(false);
        actions.setPreferredSize(new Dimension(700, 238));
        actions.add(actionCard("SEND FILES", "Choose a nearby computer on the same Wi-Fi and send files directly.", "↑", BLUE,
                "Send files  →", this::openSendPage));
        actions.add(actionCard("RECEIVE FILES", "Start your receiver so other computers on this Wi-Fi can find you.", "↓", GREEN,
                "Start receiving  →", this::openReceivePage));

        JPanel body = new JPanel(new BorderLayout(0, 16));
        body.setOpaque(false);
        JPanel stats = new JPanel(new GridLayout(1, 3, 14, 0));
        stats.setOpaque(false);
        stats.add(groupStatusCard());
        stats.add(statCard("CONNECTED", clientCount, "Active clients"));
        JPanel totals = rounded(Color.WHITE, 16);
        totals.setLayout(new GridLayout(2, 1, 0, 2));
        totals.setBorder(new EmptyBorder(16, 18, 14, 18));
        JLabel totalTitle = smallCaps("TRANSFERRED");
        JPanel combined = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        combined.setOpaque(false);
        styleMetric(fileTotal, 22);
        combined.add(fileTotal);
        JLabel filesLabel = new JLabel("files  ·");
        filesLabel.setForeground(MUTED);
        combined.add(filesLabel);
        styleMetric(byteTotal, 18);
        combined.add(byteTotal);
        totals.add(totalTitle);
        totals.add(combined);
        stats.add(totals);

        JPanel lower = new JPanel(new BorderLayout(0, 14));
        lower.setOpaque(false);
        JPanel recent = rounded(Color.WHITE, 16);
        recent.setLayout(new BorderLayout(0, 10));
        recent.setBorder(new EmptyBorder(17, 19, 17, 19));
        recent.add(sectionTitle("Recent transfers", "Latest files received by this computer"), BorderLayout.NORTH);
        recentTransfers.setForeground(INK);
        recentTransfers.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        recent.add(recentTransfers, BorderLayout.CENTER);
        activity.setEditable(false);
        activity.setRows(3);
        activity.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        activity.setForeground(MUTED);
        activity.setBackground(new Color(249, 250, 252));
        lower.add(recent, BorderLayout.NORTH);
        JPanel activityCard = rounded(Color.WHITE, 16);
        activityCard.setLayout(new BorderLayout(0, 8));
        activityCard.setBorder(new EmptyBorder(13, 18, 13, 18));
        activityCard.add(sectionTitle("Activity", "Live receiver and transfer updates"), BorderLayout.NORTH);
        activityCard.add(new JScrollPane(activity), BorderLayout.CENTER);
        lower.add(activityCard, BorderLayout.CENTER);
        body.add(stats, BorderLayout.NORTH);
        body.add(lower, BorderLayout.CENTER);
        page.add(actions, BorderLayout.NORTH);
        page.add(body, BorderLayout.CENTER);
        return page;
    }

    private JPanel groupStatusCard() {
        JPanel card = rounded(Color.WHITE, 16);
        card.setLayout(new BorderLayout(8, 4));
        card.setBorder(new EmptyBorder(12, 15, 12, 13));
        card.add(smallCaps("RECEIVER STATUS"), BorderLayout.NORTH);
        JPanel details = new JPanel();
        details.setOpaque(false);
        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
        styleMetric(status, 15);
        details.add(status);
        details.add(Box.createVerticalStrut(2));
        receiverOverview.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        receiverOverview.setForeground(MUTED);
        details.add(receiverOverview);
        card.add(details, BorderLayout.CENTER);
        return card;
    }

    private JPanel actionCard(String eyebrow, String description, String symbol, Color accent,
                              String buttonText, Runnable action) {
        JPanel card = rounded(accent, 22);
        card.setLayout(new BorderLayout(12, 0));
        card.setBorder(new EmptyBorder(25, 25, 25, 24));
        JPanel icon = new JPanel(new GridBagLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(255, 255, 255, 48));
                g2.fillOval(0, 0, getWidth(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        icon.setOpaque(false);
        icon.setPreferredSize(new Dimension(112, 112));
        icon.setMinimumSize(new Dimension(112, 112));
        icon.setMaximumSize(new Dimension(112, 112));
        JLabel glyph = new JLabel(symbol);
        glyph.setFont(new Font("Segoe UI Symbol", Font.BOLD, 48));
        glyph.setForeground(Color.WHITE);
        icon.add(glyph);

        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        JLabel label = new JLabel(eyebrow);
        label.setFont(new Font("Segoe UI", Font.BOLD, 13));
        label.setForeground(Color.WHITE);
        JLabel detail = new JLabel("<html><div style='width:210px'>" + description + "</div></html>");
        detail.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        detail.setForeground(new Color(244, 247, 255));
        detail.setBorder(new EmptyBorder(9, 0, 16, 0));
        JButton button = new JButton(buttonText);
        button.setFont(new Font("Segoe UI", Font.BOLD, 14));
        button.setForeground(accent);
        button.setBackground(Color.WHITE);
        button.setFocusPainted(false);
        button.setBorder(new EmptyBorder(14, 20, 14, 20));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.addActionListener(e -> action.run());
        copy.add(label);
        copy.add(detail);
        copy.add(button);
        JPanel iconSlot = new JPanel(new GridBagLayout());
        iconSlot.setOpaque(false);
        iconSlot.setPreferredSize(new Dimension(124, 124));
        iconSlot.add(icon);
        card.add(iconSlot, BorderLayout.WEST);
        card.add(copy, BorderLayout.CENTER);
        return card;
    }

    private JPanel statCard(String caption, JLabel value, String foot) {
        JPanel card = rounded(Color.WHITE, 16);
        card.setLayout(new GridLayout(3, 1, 0, 2));
        card.setBorder(new EmptyBorder(14, 18, 13, 16));
        card.add(smallCaps(caption));
        styleMetric(value, 18);
        card.add(value);
        JLabel hint = new JLabel(foot);
        hint.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        hint.setForeground(MUTED);
        card.add(hint);
        return card;
    }

    private JPanel sendPage() {
        JPanel page = new JPanel(new BorderLayout(0, 16));
        page.setOpaque(false);
        JPanel connection = rounded(Color.WHITE, 16);
        connection.setLayout(new BorderLayout(0, 14));
        connection.setBorder(new EmptyBorder(19, 21, 19, 21));
        connection.add(sectionTitle("Connect to a group peer", "Find a peer automatically or enter its address"), BorderLayout.NORTH);
        JPanel fields = new JPanel(new GridBagLayout());
        fields.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(0, 0, 0, 12);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.gridx = 0;
        c.gridy = 0;
        fields.add(fieldBlock("PEER ADDRESS OR IP", host), c);
        c.gridx = 1;
        c.weightx = 0.25;
        fields.add(fieldBlock("PORT", port), c);
        connection.add(fields, BorderLayout.CENTER);
        JPanel discoveryPanel = new JPanel(new BorderLayout(8, 8));
        discoveryPanel.setOpaque(false);
        JPanel discoveryActions = new JPanel(new BorderLayout(8, 0));
        discoveryActions.setOpaque(false);
        JLabel discoveryHint = new JLabel("Find receivers connected to the same Wi-Fi network");
        discoveryHint.setForeground(MUTED);
        discoveryHint.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        discoveryActions.add(discoveryHint, BorderLayout.CENTER);
        JButton scan = secondaryButton("Find devices");
        scan.addActionListener(e -> scanForDevices(scan));
        discoveryActions.add(scan, BorderLayout.EAST);
        discoveryPanel.add(discoveryActions, BorderLayout.NORTH);
        deviceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        deviceList.setVisibleRowCount(2);
        deviceList.setFixedCellHeight(30);
        deviceList.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        deviceList.setSelectionBackground(new Color(231, 237, 255));
        deviceList.setSelectionForeground(INK);
        JScrollPane deviceScroll = new JScrollPane(deviceList);
        deviceScroll.setPreferredSize(new Dimension(200, 64));
        deviceScroll.setBorder(BorderFactory.createLineBorder(new Color(230, 234, 242)));
        discoveryPanel.add(deviceScroll, BorderLayout.CENTER);
        JButton connect = primaryButton("Connect to selected device");
        connect.addActionListener(e -> selectDiscoveredDevice());
        discoveryPanel.add(connect, BorderLayout.SOUTH);
        connection.add(discoveryPanel, BorderLayout.SOUTH);

        JPanel transfer = rounded(Color.WHITE, 16);
        transfer.setLayout(new BorderLayout(0, 14));
        transfer.setBorder(new EmptyBorder(19, 21, 19, 21));
        transfer.add(sectionTitle("Choose what to send", "Files are transferred directly over your local network"), BorderLayout.NORTH);
        JPanel sendContent = new JPanel();
        sendContent.setOpaque(false);
        sendContent.setLayout(new BoxLayout(sendContent, BoxLayout.Y_AXIS));
        JButton choose = primaryButton("＋   Choose a file");
        choose.setAlignmentX(Component.LEFT_ALIGNMENT);
        choose.addActionListener(e -> chooseAndSend());
        transferProgress.setStringPainted(true);
        transferProgress.setString("Ready");
        transferProgress.setForeground(BLUE);
        transferProgress.setBackground(new Color(232, 236, 246));
        transferProgress.setBorderPainted(false);
        transferMessage.setForeground(MUTED);
        transferMessage.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        sendContent.add(choose);
        sendContent.add(Box.createVerticalStrut(20));
        sendContent.add(transferMessage);
        sendContent.add(Box.createVerticalStrut(8));
        sendContent.add(transferProgress);
        transfer.add(sendContent, BorderLayout.CENTER);

        JLabel tip = new JLabel("Both computers must be connected to the same Wi-Fi network.");
        tip.setForeground(MUTED);
        tip.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        page.add(connection, BorderLayout.NORTH);
        JPanel middle = new JPanel(new BorderLayout(0, 12));
        middle.setOpaque(false);
        middle.add(transfer, BorderLayout.CENTER);
        middle.add(tip, BorderLayout.SOUTH);
        page.add(middle, BorderLayout.CENTER);
        return page;
    }

    private JPanel receivePage() {
        JPanel page = new JPanel(new BorderLayout(0, 16));
        page.setOpaque(false);
        JPanel card = rounded(Color.WHITE, 18);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(24, 26, 25, 26));
        card.add(sectionTitle("Receive files", "Keep this screen open while your receiver is running"));
        card.add(Box.createVerticalStrut(22));

        receiverPageStatus.setFont(new Font("Segoe UI", Font.BOLD, 18));
        receiverPageStatus.setForeground(MUTED);
        receiverPageStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(receiverPageStatus);
        card.add(Box.createVerticalStrut(8));
        JLabel instruction = new JLabel("Make sure the sender is connected to the same Wi-Fi network.");
        instruction.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        instruction.setForeground(MUTED);
        card.add(instruction);
        card.add(Box.createVerticalStrut(26));

        receiveMessage.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        receiveMessage.setForeground(INK);
        receiveProgress.setStringPainted(true);
        receiveProgress.setString("");
        receiveProgress.setForeground(GREEN);
        receiveProgress.setBackground(new Color(232, 236, 246));
        receiveProgress.setBorderPainted(false);
        receiveProgress.setPreferredSize(new Dimension(640, 24));
        receiveProgress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        receiveProgress.setVisible(false);
        receiveMessage.setAlignmentX(Component.LEFT_ALIGNMENT);
        receiveProgress.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(receiveMessage);
        card.add(Box.createVerticalStrut(10));
        card.add(receiveProgress);

        JButton start = primaryButton("Start receiving");
        start.setAlignmentX(Component.LEFT_ALIGNMENT);
        start.addActionListener(event -> startPeerServices());
        card.add(Box.createVerticalStrut(20));
        card.add(start);

        page.add(card, BorderLayout.NORTH);
        return page;
    }

    private JPanel fieldBlock(String caption, JTextField field) {
        JPanel block = new JPanel(new BorderLayout(0, 7));
        block.setOpaque(false);
        block.add(smallCaps(caption), BorderLayout.NORTH);
        field.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        field.setForeground(INK);
        field.setBackground(new Color(248, 249, 252));
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(224, 229, 238)), new EmptyBorder(9, 11, 9, 11)));
        block.add(field, BorderLayout.CENTER);
        return block;
    }

    private JPanel historyPage() {
        JPanel page = new JPanel(new BorderLayout(0, 14));
        page.setOpaque(false);
        JPanel searchCard = rounded(Color.WHITE, 15);
        searchCard.setLayout(new BorderLayout(10, 0));
        searchCard.setBorder(new EmptyBorder(12, 15, 12, 15));
        JTextField search = new JTextField();
        search.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        search.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(224, 229, 238)), new EmptyBorder(9, 10, 9, 10)));
        search.putClientProperty("JTextField.placeholderText", "Search by filename");
        JButton searchButton = secondaryButton("Search");
        searchCard.add(search, BorderLayout.CENTER);
        searchCard.add(searchButton, BorderLayout.EAST);

        JTable table = new JTable(historyRows);
        table.setRowHeight(38);
        table.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        table.setForeground(INK);
        table.setGridColor(new Color(238, 241, 246));
        table.setShowVerticalLines(false);
        table.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 11));
        table.getTableHeader().setForeground(MUTED);
        table.getTableHeader().setBackground(new Color(249, 250, 252));
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        JPanel tableCard = rounded(Color.WHITE, 15);
        tableCard.setLayout(new BorderLayout());
        tableCard.setBorder(new EmptyBorder(8, 8, 8, 8));
        tableCard.add(scroll);
        Runnable filter = () -> filterHistory(search.getText());
        searchButton.addActionListener(e -> filter.run());
        search.addActionListener(e -> filter.run());
        page.add(searchCard, BorderLayout.NORTH);
        page.add(tableCard, BorderLayout.CENTER);
        return page;
    }

    private JPanel settingsPage() {
        JPanel page = new JPanel(new BorderLayout());
        page.setOpaque(false);
        JPanel card = rounded(Color.WHITE, 16);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(22, 24, 22, 24));
        card.add(sectionTitle("Receiving preferences", "Choose where incoming files are saved"));
        card.add(Box.createVerticalStrut(22));
        JLabel directoryLabel = new JLabel("SAVE FILES TO");
        directoryLabel.setFont(new Font("Segoe UI", Font.BOLD, 10));
        directoryLabel.setForeground(MUTED);
        card.add(directoryLabel);
        card.add(Box.createVerticalStrut(7));
        downloadPath.setText(downloads.toAbsolutePath().toString());
        downloadPath.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        downloadPath.setForeground(INK);
        card.add(downloadPath);
        card.add(Box.createVerticalStrut(11));
        JButton browse = secondaryButton("Choose folder");
        browse.setAlignmentX(Component.LEFT_ALIGNMENT);
        browse.addActionListener(e -> {
            Path selected = choosePath(downloads.toFile(), JFileChooser.DIRECTORIES_ONLY,
                    "Choose a download folder", "Incoming files will be saved in this folder.", "Use this folder");
            if (selected != null) {
                downloads = selected;
                downloadPath.setText(downloads.toAbsolutePath().toString());
            }
        });
        card.add(browse);
        card.add(Box.createVerticalStrut(25));
        card.add(sectionTitle("File size", "Send any file that fits in the available disk space"));
        card.add(Box.createVerticalStrut(12));
        JLabel limit = new JLabel("Application file-size cap     None");
        limit.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        limit.setForeground(INK);
        card.add(limit);
        JLabel apply = new JLabel("Folder changes apply the next time you start the receiver.");
        apply.setForeground(MUTED);
        apply.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        card.add(Box.createVerticalStrut(7));
        card.add(apply);
        page.add(card, BorderLayout.NORTH);
        return page;
    }

    private JPanel sectionTitle(String title, String subtitle) {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JLabel heading = new JLabel(title);
        heading.setFont(new Font("Segoe UI", Font.BOLD, 15));
        heading.setForeground(INK);
        JLabel detail = new JLabel(subtitle);
        detail.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        detail.setForeground(MUTED);
        panel.add(heading);
        panel.add(Box.createVerticalStrut(4));
        panel.add(detail);
        return panel;
    }

    private JLabel smallCaps(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Segoe UI", Font.BOLD, 10));
        label.setForeground(MUTED);
        return label;
    }

    private void styleMetric(JLabel label, int size) {
        label.setFont(new Font("Segoe UI", Font.BOLD, size));
        label.setForeground(INK);
    }

    private JButton primaryButton(String text) {
        JButton button = new JButton(text);
        button.setFont(new Font("Segoe UI", Font.BOLD, 12));
        button.setForeground(Color.WHITE);
        button.setBackground(BLUE);
        button.setFocusPainted(false);
        button.setBorder(new EmptyBorder(11, 17, 11, 17));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private JButton secondaryButton(String text) {
        JButton button = new JButton(text);
        button.setFont(new Font("Segoe UI", Font.BOLD, 12));
        button.setForeground(INK);
        button.setBackground(new Color(241, 244, 250));
        button.setFocusPainted(false);
        button.setBorder(new EmptyBorder(9, 14, 9, 14));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private JPanel rounded(Color color, int radius) {
        JPanel panel = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        panel.setOpaque(false);
        return panel;
    }

    private void showPage(String key, String title) {
        pageTitle.setText(title);
        pageLayout.show(pages, key);
        if (key.equals("history")) filterHistory("");
    }

    private void openSendPage() {
        showPage("send", "Send files");
    }

    private void openReceivePage() {
        if (startPeerServices()) showPage("receive", "Receive files");
    }

    private boolean startPeerServices() {
        if (server != null && server.isRunning()) return true;
        try {
            int serverPort = Integer.parseInt(port.getText().trim());
            if (!FileValidator.validPort(serverPort)) throw new IllegalArgumentException("Port must be between 1 and 65535");
            FileServer next = new FileServer(serverPort, downloads, repository,
                    this::serverEvent, this::updateIncomingProgress);
            next.start();
            server = next;
            if (lanDiscovery != null) lanDiscovery.close();
            lanDiscovery = new LanDiscovery(serverPort);
            try {
                lanDiscovery.start();
            } catch (Exception discoveryError) {
                serverEvent("Device discovery unavailable: " + discoveryError.getMessage());
            }
            status.setText("Listening on port " + serverPort);
            status.setForeground(GREEN);
            receiverPageStatus.setText("Listening on port " + serverPort);
            receiverPageStatus.setForeground(GREEN);
            receiverOverview.setText("Available to devices on the same Wi-Fi");
            return true;
        } catch (Exception ex) {
            showMessage("Could not start the receiver: " + ex.getMessage());
            return false;
        }
    }

    private void stopPeerServices() {
        FileServer activeServer = server;
        server = null;
        if (activeServer != null) activeServer.stop();
        LanDiscovery activeDiscovery = lanDiscovery;
        lanDiscovery = null;
        if (activeDiscovery != null) activeDiscovery.close();
        FileClient.Connection activeConnection = outgoingConnection;
        outgoingConnection = null;
        if (activeConnection != null) {
            try { activeConnection.close(); }
            catch (Exception ex) { activity.append("Could not close peer connection: " + ex.getMessage() + "\n"); }
        }
        status.setText("Receiver stopped");
        status.setForeground(MUTED);
        receiverPageStatus.setText("Receiver stopped");
        receiverPageStatus.setForeground(MUTED);
        clientCount.setText("0");
        receiveMessage.setText("Waiting for an incoming file");
        receiverOverview.setText("Start receiving to accept files");
        receiveProgress.setValue(0);
        receiveProgress.setString("");
        receiveProgress.setVisible(false);
    }

    private void updateIncomingProgress(long received, long total) {
        if (received == 0) receiveStartedAt = System.nanoTime();
        double elapsed = Math.max(0.001, (System.nanoTime() - receiveStartedAt) / 1_000_000_000.0);
        long bytesPerSecond = (long) (received / elapsed);
        SwingUtilities.invokeLater(() -> {
            int percent = total == 0 ? 100 : (int) Math.min(100, received * 100.0 / total);
            receiveProgress.setVisible(true);
            receiveProgress.setValue(percent);
            receiveProgress.setString(percent + "%  ·  " + formatBytes(bytesPerSecond) + "/s");
            receiveMessage.setText("Receiving " + formatBytes(received) + " of " + formatBytes(total)
                    + "  ·  " + formatBytes(bytesPerSecond) + "/s");
            receiverOverview.setText("Receiving a file · " + percent + "% complete");
        });
    }

    private void serverEvent(String event) {
        SwingUtilities.invokeLater(() -> {
            activity.append(event + "\n");
            activity.setCaretPosition(activity.getDocument().getLength());
            FileServer active = server;
            clientCount.setText(String.valueOf(active == null ? 0 : active.connectedClients()));
            if (event.startsWith("Received ")) {
                receiveMessage.setText(event);
                receiveProgress.setValue(100);
                receiveProgress.setString("Complete");
                receiverOverview.setText("File received successfully");
            }
            refreshHistory();
        });
    }

    private void chooseAndSend() {
        Path selected = choosePath(downloads.toFile(), JFileChooser.FILES_ONLY,
                "Choose a file to send", "Select one file to send over the same Wi-Fi network.", "Choose file");
        if (selected != null) send(selected);
    }

    private Path choosePath(java.io.File directory, int selectionMode, String title,
                            String hint, String approveText) {
        JFileChooser chooser = new JFileChooser(directory);
        chooser.setDialogTitle(title);
        chooser.setApproveButtonText(approveText);
        chooser.setFileSelectionMode(selectionMode);
        chooser.setMultiSelectionEnabled(false);
        chooser.setPreferredSize(new Dimension(760, 500));
        chooser.setBackground(BACKGROUND);
        chooser.setAccessory(fileChooserHint(hint));
        chooser.setControlButtonsAreShown(false);

        Path[] chosenPath = new Path[1];
        JDialog dialog = new JDialog(this, title, true);
        dialog.setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(0, 14));
        content.setBackground(Color.WHITE);
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(224, 229, 238)), new EmptyBorder(18, 20, 16, 20)));
        content.add(sectionTitle(title, hint), BorderLayout.NORTH);
        content.add(chooser, BorderLayout.CENTER);

        JLabel selectionHint = new JLabel("Select an item to continue.");
        selectionHint.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        selectionHint.setForeground(MUTED);
        JPanel footer = new JPanel(new BorderLayout(12, 0));
        footer.setOpaque(false);
        footer.add(selectionHint, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        JButton cancel = secondaryButton("Cancel");
        cancel.addActionListener(event -> dialog.dispose());
        JButton approve = primaryButton(approveText);
        approve.addActionListener(event -> {
            java.io.File selected = chooser.getSelectedFile();
            boolean valid = selected != null && (selectionMode != JFileChooser.DIRECTORIES_ONLY || selected.isDirectory())
                    && (selectionMode != JFileChooser.FILES_ONLY || selected.isFile());
            if (!valid) {
                selectionHint.setText(selectionMode == JFileChooser.DIRECTORIES_ONLY
                        ? "Select a folder to continue." : "Select a file to continue.");
                return;
            }
            chosenPath[0] = selected.toPath();
            dialog.dispose();
        });
        buttons.add(cancel);
        buttons.add(approve);
        footer.add(buttons, BorderLayout.EAST);
        content.add(footer, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.setSize(850, 590);
        dialog.setMinimumSize(new Dimension(760, 520));
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
        return chosenPath[0];
    }

    private JPanel fileChooserHint(String message) {
        JPanel card = rounded(new Color(247, 249, 253), 14);
        card.setPreferredSize(new Dimension(170, 100));
        card.setLayout(new BorderLayout(0, 8));
        card.setBorder(new EmptyBorder(14, 12, 14, 12));
        JLabel title = smallCaps("SHARELAN");
        JLabel hint = new JLabel("<html><div style='width:140px'>" + message + "</div></html>");
        hint.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        hint.setForeground(INK);
        card.add(title, BorderLayout.NORTH);
        card.add(hint, BorderLayout.CENTER);
        return card;
    }

    private void scanForDevices(JButton scanButton) {
        scanButton.setEnabled(false);
        scanButton.setText("Searching…");
        deviceModel.clear();
        background.submit(() -> {
            List<LanDiscovery.Device> devices;
            String failure = null;
            try { devices = LanDiscovery.findReceivers(); }
            catch (Exception ex) { devices = List.of(); failure = ex.getMessage(); }
            List<LanDiscovery.Device> discovered = devices;
            String error = failure;
            SwingUtilities.invokeLater(() -> {
                scanButton.setEnabled(true);
                scanButton.setText("Find devices");
                discovered.forEach(deviceModel::addElement);
                if (error != null) transferMessage.setText("Device search failed · " + error);
                else if (discovered.isEmpty()) transferMessage.setText("No peers found · check the same Wi-Fi connection and try again");
                else transferMessage.setText("Found " + discovered.size() + " peer(s) · select one and connect");
            });
        });
    }

    private void selectDiscoveredDevice() {
        LanDiscovery.Device selected = deviceList.getSelectedValue();
        if (selected == null) {
            showMessage("Find devices, then select a peer from the list.");
            return;
        }
        host.setText(selected.address());
        port.setText(String.valueOf(selected.port()));
        transferMessage.setText("Connecting to " + selected.name() + "…");
        showPage("send", "Send files");
        background.submit(() -> {
            try {
                FileClient.Connection previous = outgoingConnection;
                if (previous != null) previous.close();
                FileClient.Connection connected = FileClient.connect(selected.address(), selected.port());
                outgoingConnection = connected;
                SwingUtilities.invokeLater(() -> transferMessage.setText(
                        "Connected to " + selected.name() + " · choose a file to send"));
            } catch (Exception ex) {
                outgoingConnection = null;
                SwingUtilities.invokeLater(() -> {
                    transferMessage.setText("Could not connect · " + ex.getMessage());
                    showMessage("Could not connect to " + selected.address() + ": " + ex.getMessage());
                });
            }
        });
    }

    private void send(Path file) {
        String targetHost = host.getText().trim();
        int targetPort;
        try {
            targetPort = Integer.parseInt(port.getText().trim());
            if (!FileValidator.validPort(targetPort)) throw new IllegalArgumentException("Port must be between 1 and 65535");
        } catch (Exception ex) {
            showMessage("Enter a valid server port.");
            return;
        }
        transferMessage.setText("Sending " + file.getFileName());
        transferProgress.setValue(0);
        transferProgress.setString("Connecting…");
        background.submit(() -> {
            FileClient.Connection usedConnection = null;
            try {
                long size = Files.size(file);
                long startedAt = System.nanoTime();
                FileClient.Connection active = outgoingConnection;
                if (active == null || !active.isOpen()) active = FileClient.connect(targetHost, targetPort);
                usedConnection = active;
                usedConnection.send(file, sent -> SwingUtilities.invokeLater(() -> {
                    int percent = size == 0 ? 100 : (int) Math.min(100, sent * 100.0 / size);
                    double elapsedSeconds = Math.max(0.001, (System.nanoTime() - startedAt) / 1_000_000_000.0);
                    long bytesPerSecond = (long) (sent / elapsedSeconds);
                    transferProgress.setValue(percent);
                    transferProgress.setString(percent + "%  ·  " + formatBytes(bytesPerSecond) + "/s");
                    transferMessage.setText("Sending " + file.getFileName() + "  ·  "
                            + formatBytes(sent) + " of " + formatBytes(size) + "  ·  "
                            + formatBytes(bytesPerSecond) + "/s");
                }));
                usedConnection.close();
                outgoingConnection = null;
                SwingUtilities.invokeLater(() -> {
                    double elapsedSeconds = Math.max(0.001, (System.nanoTime() - startedAt) / 1_000_000_000.0);
                    long averageSpeed = (long) (size / elapsedSeconds);
                    transferMessage.setText("Sent successfully · " + file.getFileName() + " · average "
                            + formatBytes(averageSpeed) + "/s");
                    transferProgress.setValue(100);
                    transferProgress.setString("Complete  ·  " + formatBytes(averageSpeed) + "/s");
                });
            } catch (Exception ex) {
                if (usedConnection != null) try { usedConnection.close(); } catch (Exception closeError) {
                    System.err.println("Could not close failed transfer socket: " + closeError.getMessage());
                }
                FileClient.Connection active = outgoingConnection;
                outgoingConnection = null;
                if (active != null) try { active.close(); } catch (Exception closeError) {
                    System.err.println("Could not close failed send connection: " + closeError.getMessage());
                }
                SwingUtilities.invokeLater(() -> {
                    transferMessage.setText("Transfer failed · " + ex.getMessage());
                    transferProgress.setString("Failed");
                    showMessage("Send failed: " + ex.getMessage());
                });
            }
        });
    }

    private void refreshHistory() {
        try {
            List<Transfer> transfers = repository.load();
            long bytes = transfers.stream().mapToLong(Transfer::size).sum();
            fileTotal.setText(String.valueOf(transfers.size()));
            byteTotal.setText(formatBytes(bytes));
            recentTransfers.setText(transfers.isEmpty() ? "No transfers yet" : String.join("   ·   ",
                    transfers.stream().skip(Math.max(0, transfers.size() - 3L)).map(Transfer::filename).toList()));
            filterHistory("");
        } catch (Exception ex) {
            activity.append("History unavailable: " + ex.getMessage() + "\n");
        }
    }

    private void filterHistory(String query) {
        try {
            String needle = query.toLowerCase(Locale.ROOT);
            historyRows.setRowCount(0);
            for (Transfer t : repository.load()) {
                if (t.filename().toLowerCase(Locale.ROOT).contains(needle))
                    historyRows.addRow(new Object[]{t.filename(), t.sender(), formatBytes(t.size()), t.dateTime()});
            }
        } catch (Exception ex) {
            activity.append("History search failed: " + ex.getMessage() + "\n");
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private void showMessage(String text) {
        JDialog dialog = new JDialog(this, "ShareLAN", true);
        dialog.setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(0, 16));
        content.setBackground(Color.WHITE);
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(224, 229, 238)), new EmptyBorder(22, 24, 20, 24)));
        JLabel eyebrow = smallCaps("SHARELAN MESSAGE");
        JLabel message = new JLabel("<html><div style='width:340px'>"
                + escapeHtml(text == null ? "" : text).replace("\n", "<br>") + "</div></html>");
        message.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        message.setForeground(INK);
        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        copy.add(eyebrow);
        copy.add(Box.createVerticalStrut(9));
        copy.add(message);
        content.add(copy, BorderLayout.CENTER);

        JButton close = primaryButton("Got it");
        close.addActionListener(event -> dialog.dispose());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.setOpaque(false);
        actions.add(close);
        content.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(420, dialog.getHeight()));
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Main().setVisible(true));
    }
}
