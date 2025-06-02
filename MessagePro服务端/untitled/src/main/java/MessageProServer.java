import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Copyright ©2025 George , All Rights Reserved.
 * 作者: george06350
 * 项目: MessagePro
 * 日期: 2025-06-02
 * 说明: [此文件为 MessagePro 项目源码，禁止用于商业用途。]
 */

public class MessageProServer {
    private static final int PORT = 12345;
    private static final String ACCOUNT_DIR = "./account"; // 账号文件夹

    private JFrame frame;
    private JTextArea logArea;
    private JLabel clientCountLabel;
    private DefaultListModel<String> userListModel;
    private JList<String> userList;
    private JButton kickButton, muteButton, unmuteButton;

    private final Map<String, ClientHandler> userHandlers = new ConcurrentHashMap<>();

    public MessageProServer() {
        File accountDir = new File(ACCOUNT_DIR);
        if (!accountDir.exists()) accountDir.mkdirs();

        initGUI();
        new ServerThread().start();
    }

    private void initGUI() {
        frame = new JFrame("MessagePro 服务端（含管理功能）");
        logArea = new JTextArea(20, 50);
        logArea.setEditable(false);
        logArea.setLineWrap(true);

        JScrollPane scrollPane = new JScrollPane(logArea);

        clientCountLabel = new JLabel("当前在线客户端：0");

        userListModel = new DefaultListModel<>();
        userList = new JList<>(userListModel);
        userList.setFixedCellWidth(150);
        userList.setVisibleRowCount(12);

        JScrollPane userScroll = new JScrollPane(userList);
        userScroll.setPreferredSize(new Dimension(150, 240));

        kickButton = new JButton("踢人");
        muteButton = new JButton("禁言");
        unmuteButton = new JButton("解禁");

        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setPreferredSize(new Dimension(170, 340));
        rightPanel.add(new JLabel("在线用户："), BorderLayout.NORTH);
        rightPanel.add(userScroll, BorderLayout.CENTER);
        JPanel btnPanel = new JPanel(new GridLayout(3, 1, 5, 5));
        btnPanel.add(kickButton);
        btnPanel.add(muteButton);
        btnPanel.add(unmuteButton);
        rightPanel.add(btnPanel, BorderLayout.SOUTH);

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(clientCountLabel, BorderLayout.WEST);

        frame.setLayout(new BorderLayout());
        frame.add(topPanel, BorderLayout.NORTH);
        frame.add(scrollPane, BorderLayout.CENTER);
        frame.add(rightPanel, BorderLayout.EAST);

        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setPreferredSize(new Dimension(800, 480));
        frame.setResizable(false);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        appendLog("服务端已启动，监听端口：" + PORT);

        kickButton.addActionListener(e -> kickSelectedUser());
        muteButton.addActionListener(e -> muteSelectedUser());
        unmuteButton.addActionListener(e -> unmuteSelectedUser());
    }

    private void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void updateUserList() {
        SwingUtilities.invokeLater(() -> {
            userListModel.clear();
            for (String username : userHandlers.keySet()) {
                ClientHandler handler = userHandlers.get(username);
                if (handler != null && handler.isMuted) {
                    userListModel.addElement(username + "（已禁言）");
                } else {
                    userListModel.addElement(username);
                }
            }
            clientCountLabel.setText("当前在线客户端：" + userHandlers.size());
        });
    }

    private void kickSelectedUser() {
        String selected = userList.getSelectedValue();
        if (selected == null) return;
        String username = selected.replace("（已禁言）", "");
        ClientHandler handler = userHandlers.get(username);
        if (handler != null) {
            handler.kick();
            appendLog("管理员踢出了用户：" + username);
        }
    }

    private void muteSelectedUser() {
        String selected = userList.getSelectedValue();
        if (selected == null) return;
        String username = selected.replace("（已禁言）", "");
        ClientHandler handler = userHandlers.get(username);
        if (handler != null && !handler.isMuted) {
            handler.isMuted = true;
            appendLog("管理员禁言了用户：" + username);
            updateUserList();
        }
    }

    private void unmuteSelectedUser() {
        String selected = userList.getSelectedValue();
        if (selected == null) return;
        String username = selected.replace("（已禁言）", "");
        ClientHandler handler = userHandlers.get(username);
        if (handler != null && handler.isMuted) {
            handler.isMuted = false;
            appendLog("管理员解禁了用户：" + username);
            updateUserList();
        }
    }

    private class ServerThread extends Thread {
        public void run() {
            try (ServerSocket serverSocket = new ServerSocket(PORT)) {
                appendLog("等待客户端连接...");
                while (true) {
                    Socket socket = serverSocket.accept();
                    new Thread(new ClientHandler(socket)).start();
                }
            } catch (IOException e) {
                appendLog("服务器异常：" + e.getMessage());
            }
        }
    }

    class ClientHandler implements Runnable {
        private Socket socket;
        private PrintWriter out;
        private BufferedReader in;
        private String username;
        private volatile boolean isMuted = false;
        private volatile boolean isKicked = false;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        public void run() {
            try {
                out = new PrintWriter(socket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String loginMsg = in.readLine();
                if (loginMsg == null) {
                    out.println("协议错误，连接断开。");
                    socket.close();
                    return;
                }
                if (loginMsg.startsWith("REGISTER:")) {
                    // 注册流程
                    String[] regParts = loginMsg.split(":", 3);
                    if (regParts.length != 3) {
                        out.println("注册格式错误，连接断开。");
                        socket.close();
                        return;
                    }
                    String usernameInput = regParts[1].trim();
                    String passwordInput = regParts[2].trim();
                    File userFile = new File(ACCOUNT_DIR, usernameInput + ".txt");
                    if (userFile.exists()) {
                        out.println("注册失败：用户名已存在。");
                        socket.close();
                        return;
                    }
                    try (BufferedWriter writer = new BufferedWriter(new FileWriter(userFile))) {
                        writer.write(passwordInput);
                    } catch (IOException e) {
                        out.println("注册失败：服务器写入错误。");
                        socket.close();
                        return;
                    }
                    out.println("REGISTER_SUCCESS");
                    username = usernameInput;
                } else if (loginMsg.startsWith("LOGIN:")) {
                    // 登录流程
                    String[] loginParts = loginMsg.split(":", 3);
                    if (loginParts.length != 3) {
                        out.println("登录格式错误，连接断开。");
                        socket.close();
                        return;
                    }
                    String usernameInput = loginParts[1].trim();
                    String passwordInput = loginParts[2].trim();
                    File userFile = new File(ACCOUNT_DIR, usernameInput + ".txt");
                    if (!userFile.exists()) {
                        out.println("登录失败：用户不存在。");
                        socket.close();
                        return;
                    }
                    String pwdSaved;
                    try (BufferedReader reader = new BufferedReader(new FileReader(userFile))) {
                        pwdSaved = reader.readLine();
                    }
                    if (!passwordInput.equals(pwdSaved)) {
                        out.println("登录失败：密码错误。");
                        socket.close();
                        return;
                    }
                    out.println("LOGIN_SUCCESS");
                    username = usernameInput;
                } else {
                    out.println("协议错误，连接断开。");
                    socket.close();
                    return;
                }

                if (userHandlers.containsKey(username)) {
                    out.println("系统消息: 用户名已被占用，连接断开。");
                    socket.close();
                    return;
                }
                userHandlers.put(username, this);
                updateUserList();
                appendLog("用户上线：" + username + "（" + socket.getInetAddress() + "）");

                String message;
                while ((message = in.readLine()) != null && !isKicked) {
                    if (isMuted) {
                        out.println("系统消息: 你已被禁言，消息不会被转发。");
                        continue;
                    }
                    appendLog(message);
                    broadcast(message, this);
                }
            } catch (IOException e) {
                appendLog("用户离线：" + username);
            } finally {
                userHandlers.remove(username);
                updateUserList();
                try { socket.close(); } catch (IOException e) {}
            }
        }

        public void kick() {
            isKicked = true;
            out.println("系统消息: 你已被管理员踢出聊天室。");
            try { socket.close(); } catch (IOException e) {}
        }
    }

    private void broadcast(String message, ClientHandler exclude) {
        for (ClientHandler handler : userHandlers.values()) {
            if (handler != exclude && !handler.isKicked) {
                handler.out.println(message);
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(MessageProServer::new);
    }
}