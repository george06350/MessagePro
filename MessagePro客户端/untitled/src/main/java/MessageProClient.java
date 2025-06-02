import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.Base64;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.text.SimpleDateFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Copyright ©2025 George , All Rights Reserved.
 * 作者: george06350
 * 项目: MessagePro
 * 日期: 2025-06-02
 * 说明: [此文件为 MessagePro 项目源码，禁止用于商业用途。]
 */

public class MessageProClient {
    private String username;
    private String password;
    private PrintWriter out;
    private BufferedReader in;
    private JFrame frame;
    private JTextPane chatPane;
    private JTextField inputField;
    private JButton sendButton;
    private JButton uploadImageButton;
    private JLabel imagePreviewLabel;
    private BufferedImage selectedImage;
    private File selectedImageFile;
    private Socket socket;
    private static final String REG_FILE = "regs.txt";
    private static final String CACHE_DIR = "cache_img";

    // 用于图片ID和本地缓存文件的对应
    private final Map<String, File> imageIdFileMap = new HashMap<>();

    public MessageProClient(String host, int port, String username, String password, boolean isRegister) throws IOException {
        this.username = username;
        this.password = password;

        // 创建缓存文件夹
        File cacheFolder = new File(CACHE_DIR);
        if (!cacheFolder.exists()) cacheFolder.mkdirs();

        // 连接服务器
        socket = new Socket(host, port);
        out = new PrintWriter(socket.getOutputStream(), true);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        if (isRegister) {
            out.println("REGISTER:" + username + ":" + password);
            String regResult = in.readLine();
            if ("REGISTER_SUCCESS".equals(regResult)) {
                saveRegInfo(username, password);
                initGUI();
                new Thread(this::receiveMessages).start();
            } else {
                JOptionPane.showMessageDialog(null, regResult == null ? "注册失败：未知错误" : regResult);
                try { socket.close(); } catch (IOException e) {}
                System.exit(1);
            }
        } else {
            out.println("LOGIN:" + username + ":" + password);
            String loginResult = in.readLine();
            if ("LOGIN_SUCCESS".equals(loginResult)) {
                initGUI();
                new Thread(this::receiveMessages).start();
            } else {
                JOptionPane.showMessageDialog(null, loginResult == null ? "登录失败：未知错误" : loginResult);
                try { socket.close(); } catch (IOException e) {}
                System.exit(1);
            }
        }
    }

    private void saveRegInfo(String username, String password) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(REG_FILE))) {
            writer.write(username + ":" + password);
        } catch (IOException e) {}
    }

    private static String[] readRegInfo() {
        File file = new File(REG_FILE);
        if (!file.exists()) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            if (line != null && line.contains(":")) {
                String[] parts = line.split(":", 2);
                if (parts.length == 2) {
                    return new String[]{parts[0], parts[1]};
                }
            }
        } catch (IOException e) {}
        return null;
    }

    private void initGUI() {
        frame = new JFrame("MessagePro 聊天室 - 用户：" + username);

        chatPane = new JTextPane();
        chatPane.setEditable(false);
        chatPane.setContentType("text/html");
        JScrollPane scrollPane = new JScrollPane(chatPane);

        inputField = new JTextField();
        sendButton = new JButton("发送");
        uploadImageButton = new JButton("上传图片");
        imagePreviewLabel = new JLabel();
        imagePreviewLabel.setPreferredSize(new Dimension(60, 60));
        imagePreviewLabel.setHorizontalAlignment(SwingConstants.CENTER);
        imagePreviewLabel.setBorder(BorderFactory.createDashedBorder(Color.GRAY));

        JPanel inputPanel = new JPanel(new BorderLayout());
        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        leftPanel.add(uploadImageButton);
        leftPanel.add(imagePreviewLabel);
        inputPanel.add(leftPanel, BorderLayout.WEST);
        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);

        frame.setLayout(new BorderLayout());
        frame.add(scrollPane, BorderLayout.CENTER);
        frame.add(inputPanel, BorderLayout.SOUTH);

        sendButton.addActionListener(e -> sendMessageOrImage());
        inputField.addActionListener(e -> sendMessageOrImage());
        uploadImageButton.addActionListener(e -> pickImage());

        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(670, 500);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        chatPane.addHyperlinkListener(e -> {
            if (e.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                String desc = e.getDescription();
                if (desc != null && desc.startsWith("showimgfile:")) {
                    String imgId = desc.substring("showimgfile:".length());
                    File imgFile = imageIdFileMap.get(imgId);
                    if (imgFile != null && imgFile.exists()) {
                        try {
                            BufferedImage img = ImageIO.read(imgFile);
                            showImageDialog(img);
                        } catch (Exception ex) {
                            JOptionPane.showMessageDialog(frame, "打开图片失败: " + ex.getMessage());
                        }
                    } else {
                        JOptionPane.showMessageDialog(frame, "图片文件不存在或已被清理。");
                    }
                }
            }
        });
    }

    private void pickImage() {
        JFileChooser chooser = new JFileChooser();
        int result = chooser.showOpenDialog(frame);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            try {
                BufferedImage img = ImageIO.read(file);
                if (img == null) {
                    JOptionPane.showMessageDialog(frame, "请选择图片文件！");
                    return;
                }
                selectedImage = img;
                selectedImageFile = file;
                ImageIcon icon = new ImageIcon(img.getScaledInstance(60, 60, Image.SCALE_SMOOTH));
                imagePreviewLabel.setIcon(icon);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, "读取图片失败: " + ex.getMessage());
                selectedImage = null;
                selectedImageFile = null;
                imagePreviewLabel.setIcon(null);
            }
        }
    }

    private void sendMessageOrImage() {
        String msg = inputField.getText().trim();
        boolean hasImage = (selectedImage != null);
        boolean hasText = !msg.isEmpty();

        if (!hasText && !hasImage) return;

        String timeStr = getTimeString(new Date());

        String imgId = null;
        File imgFile = null;
        if (hasImage) {
            imgId = UUID.randomUUID().toString();
            try {
                imgFile = new File(CACHE_DIR, imgId + ".png");
                ImageIO.write(selectedImage, "png", imgFile);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(frame, "图片缓存保存失败: " + e.getMessage());
                selectedImage = null;
                selectedImageFile = null;
                imagePreviewLabel.setIcon(null);
                return;
            }
            imageIdFileMap.put(imgId, imgFile);
        }
        // 本地显示
        appendMessage(username, timeStr, msg, imgId, imgFile, true);

        inputField.setText("");
        imagePreviewLabel.setIcon(null);

        if (hasImage) {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(selectedImage, "png", baos);
                String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
                // 发送 [IMAGE]username:base64:msg:timeStr
                out.println("[IMAGE]" + username + ":" + base64 + ":" + msg + ":" + timeStr);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, "发送图片失败: " + ex.getMessage());
            }
            selectedImage = null;
            selectedImageFile = null;
        } else {
            // 文本消息带时间
            out.println(username + " : " + msg + " :TIMESTAMP: " + timeStr);
        }
    }

    private void receiveMessages() {
        String msg;
        try {
            while ((msg = in.readLine()) != null) {
                if (msg.startsWith("[IMAGE]")) {
                    // [IMAGE]username:base64:msg:timeStr
                    int idx1 = msg.indexOf(':', 7);
                    int idx2 = msg.indexOf(':', idx1 + 1);
                    int idx3 = msg.lastIndexOf(':');
                    if (idx1 > 0 && idx2 > idx1 && idx3 > idx2) {
                        String from = msg.substring(7, idx1);
                        String base64 = msg.substring(idx1 + 1, idx2);
                        String text = msg.substring(idx2 + 1, idx3);
                        String timeStr = msg.substring(idx3 + 1).trim();
                        // 保存图片到本地缓存
                        String imgId = UUID.randomUUID().toString();
                        File imgFile = new File(CACHE_DIR, imgId + ".png");
                        try {
                            byte[] imgBytes = Base64.getDecoder().decode(base64);
                            try (FileOutputStream fos = new FileOutputStream(imgFile)) {
                                fos.write(imgBytes);
                            }
                            imageIdFileMap.put(imgId, imgFile);
                            appendMessage(from, timeStr, text, imgId, imgFile, false);
                        } catch (Exception ex) {
                            appendMessage(from, timeStr, text + " [图片接收失败]", null, null, false);
                        }
                    }
                } else {
                    // 文本消息格式：username : msg :TIMESTAMP: timeStr
                    int sepIdx = msg.indexOf(" : ");
                    int tsIdx = msg.lastIndexOf(":TIMESTAMP:");
                    if (sepIdx > 0 && tsIdx > sepIdx) {
                        String from = msg.substring(0, sepIdx);
                        String content = msg.substring(sepIdx + 3, tsIdx).trim();
                        String timeStr = msg.substring(tsIdx + 11).trim();
                        if (!from.equals(username)) {
                            appendMessage(from, timeStr, content, null, null, false);
                        }
                    } else { // 没有时间戳，兼容老消息
                        appendMessage(null, getTimeString(new Date()), msg, null, null, false);
                    }
                }
            }
        } catch (IOException e) {
            appendMessage(null, getTimeString(new Date()), "系统消息: 与服务器连接断开。", null, null, false);
        }
    }

    /**
     * 在JTextPane中插入文本+图片消息
     * @param from 发送人
     * @param timeStr 时间字符串
     * @param msg 文本
     * @param imgId 图片唯一id
     * @param imageFile 图片文件
     * @param isSelf 是否右对齐
     */
    private void appendMessage(String from, String timeStr, String msg, String imgId, File imageFile, boolean isSelf) {
        SwingUtilities.invokeLater(() -> {
            StringBuilder html = new StringBuilder();
            String prev = chatPane.getText();
            int bodyStart = prev.indexOf("<body>");
            int bodyEnd = prev.lastIndexOf("</body>");
            String body = (bodyStart != -1 && bodyEnd != -1) ? prev.substring(bodyStart + 6, bodyEnd) : "";

            html.append(body);

            String bubbleColor = isSelf || (from != null && from.equals(username)) ? "#d0f0c0" : "#f6f6f6";
            String align = isSelf || (from != null && from.equals(username)) ? "right" : "left";
            String margin = isSelf || (from != null && from.equals(username)) ? "margin-left:60px;margin-right:0;" : "margin-left:0;margin-right:60px;";

            html.append("<div style='width:100%; clear:both; padding:2px 0;'>");
            html.append("<div style='max-width:360px; float:").append(align)
                    .append("; background:").append(bubbleColor)
                    .append("; border-radius:8px; padding:6px 12px; font-size:13px;box-shadow:1px 1px 3px #ddd; ")
                    .append(margin).append("'>");
            if (from != null && timeStr != null) {
                html.append("<div style='color:#888;font-size:12px;margin-bottom:2px;'><b>")
                        .append(from)
                        .append("</b> ")
                        .append(timeStr)
                        .append("</div>");
            }
            if (msg != null && !msg.isEmpty()) {
                html.append("<div>").append(msg.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")).append("</div>");
            }
            if (imgId != null && imageFile != null) {
                // 聊天区只显示id不显示路径
                html.append("<div style='margin-top:6px'><a href='showimgfile:").append(imgId).append("'>[图片]</a></div>");
            }
            html.append("</div><div style='clear:both;'></div></div>");
            chatPane.setText("<html><body style='font-size:13px;'>" + html + "</body></html>");
            chatPane.setCaretPosition(chatPane.getDocument().getLength());
        });
    }

    private void showImageDialog(BufferedImage img) {
        JDialog imgDialog = new JDialog(frame, "图片预览", true);
        JLabel label = new JLabel();
        JScrollPane pane = new JScrollPane(label);

        // 最大预览尺寸
        int maxW = 800, maxH = 600;
        int imgW = img.getWidth(), imgH = img.getHeight();
        int showW = imgW, showH = imgH;
        if (imgW > maxW || imgH > maxH) {
            double scale = Math.min((double)maxW/imgW, (double)maxH/imgH);
            showW = (int)(imgW * scale);
            showH = (int)(imgH * scale);
        }
        Image scaled = img.getScaledInstance(showW, showH, Image.SCALE_SMOOTH);
        label.setIcon(new ImageIcon(scaled));
        label.setPreferredSize(new Dimension(showW, showH));

        imgDialog.setContentPane(pane);
        imgDialog.setSize(showW + 32, showH + 56); // 预留滚动条等边距
        imgDialog.setMinimumSize(new Dimension(200, 120));
        imgDialog.setLocationRelativeTo(frame);
        imgDialog.setVisible(true);
    }

    private static String getTimeString(Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        return sdf.format(date);
    }

    private static Object[] showRegOrLoginDialog() {
        JTextField ipField = new JTextField("localhost");
        JTextField userField = new JTextField();
        JPasswordField pwdField = new JPasswordField();
        JCheckBox loginCheck = new JCheckBox("我已有账号，直接登录");
        Object[] message = {
                "服务器IP地址:", ipField,
                "用户名:", userField,
                "密码:", pwdField,
                loginCheck
        };
        while (true) {
            int option = JOptionPane.showConfirmDialog(null, message, "注册/登录", JOptionPane.OK_CANCEL_OPTION);
            if (option == JOptionPane.OK_OPTION) {
                String ip = ipField.getText().trim();
                String username = userField.getText().trim();
                String password = new String(pwdField.getPassword());
                boolean isLogin = loginCheck.isSelected();
                if (!ip.isEmpty() && !username.isEmpty() && !password.isEmpty()) {
                    return new Object[]{ip, username, password, isLogin};
                } else {
                    JOptionPane.showMessageDialog(null, "IP、用户名和密码不能为空！");
                }
            } else {
                System.exit(0);
            }
        }
    }

    public static void main(String[] args) {
        int port = 12345;
        String[] reginfo = readRegInfo();
        String host, username, password;
        boolean isRegister;
        if (reginfo == null) {
            Object[] input = showRegOrLoginDialog();
            host = (String) input[0];
            username = (String) input[1];
            password = (String) input[2];
            isRegister = !((Boolean) input[3]);
        } else {
            Object[] input = showRegOrLoginDialogWithAutoLogin(reginfo[0], reginfo[1]);
            host = (String) input[0];
            username = (String) input[1];
            password = (String) input[2];
            isRegister = !((Boolean) input[3]);
        }
        try {
            new MessageProClient(host, port, username, password, isRegister);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null, "无法连接服务器: " + e.getMessage());
            System.exit(1);
        }
    }

    private static Object[] showRegOrLoginDialogWithAutoLogin(String defaultUser, String defaultPwd) {
        JTextField ipField = new JTextField("localhost");
        JTextField userField = new JTextField(defaultUser);
        JPasswordField pwdField = new JPasswordField(defaultPwd);
        JCheckBox loginCheck = new JCheckBox("我已有账号，直接登录");
        loginCheck.setSelected(true);

        Object[] message = {
                "服务器IP地址:", ipField,
                "用户名:", userField,
                "密码:", pwdField,
                loginCheck
        };
        while (true) {
            int option = JOptionPane.showConfirmDialog(null, message, "登录", JOptionPane.OK_CANCEL_OPTION);
            if (option == JOptionPane.OK_OPTION) {
                String ip = ipField.getText().trim();
                String username = userField.getText().trim();
                String password = new String(pwdField.getPassword());
                boolean isLogin = loginCheck.isSelected();
                if (!ip.isEmpty() && !username.isEmpty() && !password.isEmpty()) {
                    return new Object[]{ip, username, password, isLogin};
                } else {
                    JOptionPane.showMessageDialog(null, "IP、用户名和密码不能为空！");
                }
            } else {
                System.exit(0);
            }
        }
    }
}