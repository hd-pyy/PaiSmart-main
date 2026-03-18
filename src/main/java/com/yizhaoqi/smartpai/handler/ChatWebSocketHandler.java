package com.yizhaoqi.smartpai.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.service.ChatHandler;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private final ChatHandler chatHandler;
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JwtUtils jwtUtils;
    
    // 内部指令令牌 - 可以从配置文件读取
    private static final String INTERNAL_CMD_TOKEN = "WSS_STOP_CMD_" + System.currentTimeMillis() % 1000000;

    public ChatWebSocketHandler(ChatHandler chatHandler, JwtUtils jwtUtils) {
        this.chatHandler = chatHandler;
        this.jwtUtils = jwtUtils;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        try {
            String userId = extractUserId(session);
            sessions.put(userId, session);
            logger.info("WebSocket连接已建立，用户ID: {}，会话ID: {}，URI路径: {}", 
                        userId, session.getId(), session.getUri().getPath());
        } catch (IllegalStateException ex) {
            logger.warn("拒绝WebSocket连接: {}", ex.getMessage());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String userId = extractUserId(session);
        try {
            String payload = message.getPayload();
            logger.info("接收到消息，用户ID: {}，会话ID: {}，消息长度: {}", 
                       userId, session.getId(), payload.length());
            
            // 检查是否是JSON格式的系统指令
            if (payload.trim().startsWith("{")) {
                try {
                    Map<String, Object> jsonMessage = objectMapper.readValue(payload, Map.class);
                    String messageType = (String) jsonMessage.get("type");
                    String internalToken = (String) jsonMessage.get("_internal_cmd_token");
                    
                    // 只有包含正确内部令牌的停止指令才处理
                    if ("stop".equals(messageType) && INTERNAL_CMD_TOKEN.equals(internalToken)) {
                        // 处理停止指令
                        logger.info("收到有效的停止按钮指令，用户ID: {}，会话ID: {}", userId, session.getId());
                        chatHandler.stopResponse(userId, session);
                        return;
                    }
                    
                    // 其他JSON消息当作聊天指令处理
                    logger.debug("收到JSON格式的聊天消息，尝试提取conversationId和content");
                    String type = (String) jsonMessage.get("type");
                    if ("message".equals(type)) {
                        String conversationId = (String) jsonMessage.get("conversationId");
                        String content = (String) jsonMessage.get("content");
                        if (content != null && !content.isBlank()) {
                            chatHandler.processMessage(userId, conversationId, content, session);
                            return;
                        }
                    }
                } catch (Exception jsonParseError) {
                    // JSON解析失败，当作普通文本消息处理
                    logger.debug("JSON解析失败，当作普通消息处理: {}", jsonParseError.getMessage());
                }
            }
            
            // 普通聊天消息处理（保持向下兼容）
            chatHandler.processMessage(userId, null, payload, session);
            
        } catch (Exception e) {
            logger.error("处理消息出错，用户ID: {}，会话ID: {}，错误: {}", 
                        userId, session.getId(), e.getMessage(), e);
            sendErrorMessage(session, "消息处理失败：" + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String userId = extractUserId(session);
        sessions.remove(userId);
        logger.info("WebSocket连接已关闭，用户ID: {}，会话ID: {}，状态: {}", 
                    userId, session.getId(), status);
    }

    private String extractUserId(WebSocketSession session) {
        String path = session.getUri() != null ? session.getUri().getPath() : "";
        String[] segments = path.split("/");
        String jwtToken = segments.length > 0 ? segments[segments.length - 1] : "";

        if (jwtToken == null || jwtToken.isBlank() || "undefined".equalsIgnoreCase(jwtToken)) {
            String message = String.format("WebSocket token 缺失或无效，path=%s", path);
            closeSessionSilently(session, CloseStatus.BAD_DATA, message);
            throw new IllegalStateException(message);
        }

        String username = jwtUtils.extractUsernameFromToken(jwtToken);
        if (username == null || username.isBlank()) {
            String message = "无法从JWT令牌中提取用户名";
            closeSessionSilently(session, CloseStatus.NOT_ACCEPTABLE.withReason(message), message);
            throw new IllegalStateException(message);
        }

        logger.debug("从JWT令牌中提取的用户名: {}", username);
        return username;
    }

    private void closeSessionSilently(WebSocketSession session, CloseStatus status, String logMessage) {
        try {
            logger.warn("关闭会话 {}: {}", session.getId(), logMessage);
            session.close(status);
        } catch (Exception closeError) {
            logger.debug("关闭会话时出错: {}", closeError.getMessage());
        }
    }

    private void sendErrorMessage(WebSocketSession session, String errorMessage) {
        try {
            Map<String, String> error = Map.of("error", errorMessage);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(error)));
            logger.info("已发送错误消息到会话: {}, 错误: {}", session.getId(), errorMessage);
        } catch (Exception e) {
            logger.error("发送错误消息失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 获取内部指令令牌 - 供前端调用
     */
    public static String getInternalCmdToken() {
        return INTERNAL_CMD_TOKEN;
    }
} 