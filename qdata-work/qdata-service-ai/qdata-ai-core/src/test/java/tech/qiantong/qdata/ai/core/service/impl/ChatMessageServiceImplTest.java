package tech.qiantong.qdata.ai.core.service.impl;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import tech.qiantong.qdata.ai.core.prompt.StatisticsPromptBuilder;
import tech.qiantong.qdata.ai.core.prompt.params.FactDimensionRelation;
import tech.qiantong.qdata.ai.core.service.AiConversationAccess;
import tech.qiantong.qdata.ai.core.service.IChatModelService;
import tech.qiantong.qdata.ai.core.vo.ChatMessageSendReqVO;
import tech.qiantong.qdata.ai.core.vo.ChatMessageSendRespVO;
import tech.qiantong.qdata.common.core.domain.entity.SysUser;
import tech.qiantong.qdata.common.core.domain.model.LoginUser;
import tech.qiantong.qdata.common.database.core.DbColumn;
import tech.qiantong.qdata.module.ai.controller.admin.chat.vo.AiChatMessageSaveReqVO;
import tech.qiantong.qdata.module.ai.dal.dataobject.chat.AiChatConversationDO;
import tech.qiantong.qdata.module.ai.service.chat.IAiChatConversationService;
import tech.qiantong.qdata.module.ai.service.chat.IAiChatMessageService;
import tech.qiantong.qdata.module.da.api.datasource.dto.DaDatasourceRespDTO;
import tech.qiantong.qdata.module.da.api.service.asset.IDaDatasourceApiService;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatMessageServiceImplTest {

    private final ChatMessageServiceImpl service = new ChatMessageServiceImpl();

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"[]", "null"})
    void absentAssociationsProduceNoRelations(String associations) {
        AiChatConversationDO conversation = new AiChatConversationDO();
        conversation.setAssociations(associations);

        assertTrue(service.genRelations(conversation).isEmpty());
        assertEquals(associations, conversation.getAssociations());
    }

    @Test
    void existingRelationsKeepTheirFieldMappingAndOrder() {
        AiChatConversationDO conversation = new AiChatConversationDO();
        conversation.setAssociations("""
                [{"dimensionTable":"dim_city","factColumnName":"city_id","dimensionColumnName":"id"},
                 {"dimensionTable":"dim_date","factColumnName":"day_id","dimensionColumnName":"date_id"}]
                """);

        assertEquals(List.of(
                FactDimensionRelation.builder().dimensionTable("dim_city")
                        .factColumn("city_id").dimensionColumn("id").build(),
                FactDimensionRelation.builder().dimensionTable("dim_date")
                        .factColumn("day_id").dimensionColumn("date_id").build()
        ), service.genRelations(conversation));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"[]"})
    void noDimensionConversationCompletesSendStream(String associations) {
        // 仅使用内存替身，不启动业务服务，不连接数据库或真实模型。
        IAiChatConversationService conversations = mock(IAiChatConversationService.class);
        IAiChatMessageService messages = mock(IAiChatMessageService.class);
        IDaDatasourceApiService datasources = mock(IDaDatasourceApiService.class);
        IChatModelService models = mock(IChatModelService.class);
        ChatModel model = mock(ChatModel.class);
        StatisticsPromptBuilder prompts = spy(new StatisticsPromptBuilder());
        ReflectionTestUtils.setField(service, "conversationAccess", new AiConversationAccess(conversations, messages));
        ReflectionTestUtils.setField(service, "aiChatConversationService", conversations);
        ReflectionTestUtils.setField(service, "aiChatMessageService", messages);
        ReflectionTestUtils.setField(service, "daDatasourceApiService", datasources);
        ReflectionTestUtils.setField(service, "chatModelService", models);
        ReflectionTestUtils.setField(service, "promptBuilder", prompts);

        SysUser user = new SysUser();
        user.setUserId(7L);
        LoginUser principal = new LoginUser(7L, 1L, user, Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));

        AiChatConversationDO conversation = new AiChatConversationDO();
        conversation.setId(11L);
        conversation.setUserId(7L);
        conversation.setDatasourceId(22L);
        conversation.setJoinConditionMatchFlag(true);
        conversation.setFactTableName("fact_sales");
        conversation.setFactTableComment("销售事实表");
        conversation.setAssociations(associations);
        when(conversations.getAiChatConversationById(11L)).thenReturn(conversation);
        when(messages.getChatMessageListByConversationId(11L)).thenReturn(List.of());
        when(messages.createAiChatMessage(any())).thenReturn(101L, 102L);

        DaDatasourceRespDTO datasource = new DaDatasourceRespDTO();
        datasource.setDatasourceType("MySQL");
        datasource.setDatasourceConfig("{\"dbname\":\"offline\"}");
        when(datasources.getDatasourceById(22L)).thenReturn(datasource);
        when(datasources.getDbTableColumns(22L, "fact_sales")).thenReturn(List.of(
                DbColumn.builder().colName("id").dataType("bigint").colKey(true).colComment("编号").build()));
        when(models.getChatModel(33L)).thenReturn(model);
        String firstChunk = "{\"success\":true,\"type\":\"2\",";
        String lastChunk = "\"msg\":\"离线回答\"}";
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage(firstChunk)))),
                new ChatResponse(List.of(new Generation(new AssistantMessage(lastChunk))))));

        ChatMessageSendReqVO request = ChatMessageSendReqVO.builder().conversationId(11L)
                .modelId(33L).content("{\"msg\":\"统计销售\"}").contextFlag(false).replyType("1").build();
        List<ChatMessageSendRespVO> responses = service.sendChatMessageStream(request, 7L)
                .collectList().block(Duration.ofSeconds(5));

        assertNotNull(responses);
        assertEquals(3, responses.size());
        assertEquals(firstChunk, responses.get(0).getReceive().getContent());
        assertEquals(lastChunk, responses.get(1).getReceive().getContent());
        assertEquals(101L, responses.get(0).getSend().getId());
        assertEquals(102L, responses.get(2).getReceive().getId());
        assertEquals(JSONObject.parseObject(firstChunk + lastChunk),
                JSONObject.parseObject(responses.get(2).getReceive().getContent()));

        verify(prompts).buildPrompt(any(), any(), eq(List.of()), eq(List.of()), eq("MySQL"));
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).stream(prompt.capture());
        assertTrue(prompt.getValue().getContents().contains("fact_sales"));
        assertTrue(prompt.getValue().getContents().contains("统计销售"));
        ArgumentCaptor<AiChatMessageSaveReqVO> saved = ArgumentCaptor.forClass(AiChatMessageSaveReqVO.class);
        verify(messages, times(2)).createAiChatMessage(saved.capture());
        assertEquals(request.getContent(), saved.getAllValues().get(0).getContent());
        assertEquals(101L, saved.getAllValues().get(1).getReplyId());
        assertEquals(firstChunk + lastChunk, saved.getAllValues().get(1).getContent());
        assertEquals(associations, conversation.getAssociations());
        verify(datasources).getDatasourceById(22L);
        verify(datasources).getDbTableColumns(22L, "fact_sales");
        verifyNoMoreInteractions(model, datasources);
    }
}
