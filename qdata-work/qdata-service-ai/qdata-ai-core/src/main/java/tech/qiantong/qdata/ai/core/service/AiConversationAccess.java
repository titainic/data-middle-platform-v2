package tech.qiantong.qdata.ai.core.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import tech.qiantong.qdata.common.utils.SecurityUtils;
import tech.qiantong.qdata.module.ai.dal.dataobject.chat.AiChatConversationDO;
import tech.qiantong.qdata.module.ai.dal.dataobject.chat.AiChatMessageDO;
import tech.qiantong.qdata.module.ai.service.chat.IAiChatConversationService;
import tech.qiantong.qdata.module.ai.service.chat.IAiChatMessageService;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AiConversationAccess {
    private final IAiChatConversationService conversations;
    private final IAiChatMessageService messages;

    public AiChatConversationDO requireConversation(Long conversationId) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) {
            throw new AccessDeniedException("AI session identity is missing");
        }
        AiChatConversationDO conversation = conversationId == null ? null
                : conversations.getAiChatConversationById(conversationId);
        if (conversation == null || !Objects.equals(userId, conversation.getUserId())) {
            throw new AccessDeniedException("AI conversation access denied");
        }
        return conversation;
    }

    public AiChatMessageDO requireMessage(Long messageId) {
        AiChatMessageDO message = messageId == null ? null : messages.getAiChatMessageById(messageId);
        if (message == null) {
            throw new AccessDeniedException("AI message access denied");
        }
        requireConversation(message.getConversationId());
        return message;
    }
}
