package com.back.nbe1214finalsour.domain.support;

import com.back.nbe1214finalsour.IntegrationTest;
import com.back.nbe1214finalsour.domain.support.dto.SupportAnswerRequest;
import com.back.nbe1214finalsour.domain.support.entity.CustomerSupport;
import com.back.nbe1214finalsour.domain.support.mail.SupportAnswerMailSender;
import com.back.nbe1214finalsour.domain.support.repository.CustomerSupportRepository;
import com.back.nbe1214finalsour.domain.support.service.AdminSupportService;
import com.back.nbe1214finalsour.global.async.AsyncConfig;
import com.back.nbe1214finalsour.global.security.AuthUser;
import com.back.nbe1214finalsour.global.security.Role;
import com.back.nbe1214finalsour.global.security.jwt.JwtProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.listeners.StubbingLookupListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 문의 답변 메일 비동기 발송 (SCRUM-113, 계획 Q6). 답변이 커밋된 뒤 문의 메일 전용 스레드에서 한 번 보내고, 롤백되면 보내지
 * 않으며, 발송이 실패해도 답변은 그대로다.
 *
 * <p>메일 발송은 {@link MockitoBean}으로 바꿔 실제 메일을 보내지 않는다. 그래서 개발자 셸의 {@code SPRING_MAIL_*} 환경
 * 변수가 있어도 결과가 같다.
 */
@IntegrationTest
@AutoConfigureMockMvc
class SupportAnswerMailIntegrationTest {

    private static final String TITLE_PREFIX = "SCRUM-113 ";
    private static final long WAIT_MS = 5_000;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private CustomerSupportRepository repository;
    @Autowired
    private AdminSupportService adminSupportService;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JwtProvider jwtProvider;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private ApplicationContext context;

    @Autowired
    private SupportAnswerMailSender mailSender;

    private String adminToken;

    @BeforeEach
    void setUp() {
        adminToken = jwtProvider.createAccessToken(new AuthUser(1L, 1L, 1L, Role.ADMIN), null);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM customer_support WHERE title LIKE ?", TITLE_PREFIX + "%");
    }

    @Test
    @DisplayName("답변이 커밋되면 문의 메일 전용 스레드에서 수신 이메일로 한 번 보낸다")
    void sendsAfterCommitOnMailThread() throws Exception {
        long id = insert("me@example.com", "아이템 효과가 적용되지 않아요");
        CompletableFuture<String> thread = new CompletableFuture<>();
        doAnswer(invocation -> {
            thread.complete(Thread.currentThread().getName());
            return null;
        }).when(mailSender).sendAnswer(anyString(), anyString(), anyString(), anyString());

        answer(id, "문의 답변드립니다", "확인 결과 ...").andExpect(status().isOk());

        verify(mailSender, timeout(WAIT_MS).times(1)).sendAnswer(
                "me@example.com", TITLE_PREFIX + "아이템 효과가 적용되지 않아요", "문의 답변드립니다", "확인 결과 ...");
        // 호출 기록만으로는 Answer 실행 완료를 보장하지 않으므로, 스레드 이름이 저장될 때까지 기다린다.
        assertThat(thread.get(WAIT_MS, TimeUnit.MILLISECONDS)).startsWith("support-mail-");
    }

    @Test
    @DisplayName("메일 호출이 기록되어도 콜백이 스레드 이름을 저장할 때까지 기다린다")
    void waitsForThreadCaptureAfterInvocationIsRecorded() throws Exception {
        CountDownLatch invocationRecorded = new CountDownLatch(1);
        CountDownLatch resumeCallback = new CountDownLatch(1);
        // Mockito가 호출을 기록한 뒤 Answer를 실행하기 전의 순서를 고정한다 (Issue #102).
        StubbingLookupListener pauseBeforeAnswer = event -> {
            if (Thread.currentThread().getName().startsWith("support-mail-")
                    && event.getInvocation().getMethod().getName().equals("sendAnswer")
                    && (TITLE_PREFIX + "아이템 효과가 적용되지 않아요").equals(event.getInvocation().getArgument(1))) {
                invocationRecorded.countDown();
                try {
                    assertThat(resumeCallback.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
        };
        var listeners = mockingDetails(mailSender).getMockCreationSettings().getStubbingLookupListeners();
        listeners.add(pauseBeforeAnswer);
        try (ExecutorService verifier = Executors.newSingleThreadExecutor()) {
            var verification = verifier.submit(() -> {
                sendsAfterCommitOnMailThread();
                return null;
            });
            try {
                assertThat(invocationRecorded.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
                // 호출 기록만 보고 검증이 끝나서는 안 된다. 콜백이 멈춘 동안에는 아직 기다려야 한다.
                assertThatThrownBy(() -> verification.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                resumeCallback.countDown();
                verification.get(WAIT_MS, TimeUnit.MILLISECONDS);
            } finally {
                resumeCallback.countDown();
            }
        } finally {
            // 실패해도 비동기 작업과 listener를 다음 테스트에 남기지 않는다.
            resumeCallback.countDown();
            try {
                context.getBean(AsyncConfig.SUPPORT_MAIL_EXECUTOR, ThreadPoolTaskExecutor.class)
                        .submit(() -> {}).get(WAIT_MS, TimeUnit.MILLISECONDS);
            } finally {
                listeners.remove(pauseBeforeAnswer);
            }
        }
    }

    @Test
    @DisplayName("답변 트랜잭션이 롤백되면 메일을 보내지 않는다")
    void doesNotSendOnRollback() throws Exception {
        long rolledBack = insert("rollback@example.com", "롤백될 답변");
        long committed = insert("commit@example.com", "커밋될 답변");

        transactionTemplate.executeWithoutResult(tx -> {
            adminSupportService.answer(1L, rolledBack, new SupportAnswerRequest("답변", "내용"));
            tx.setRollbackOnly();
        });
        // 커밋되는 답변의 메일이 도착할 때까지 기다린 뒤, 롤백된 답변의 메일이 없음을 확인한다 (메일 스레드는 순서대로 처리)
        answer(committed, "답변", "내용").andExpect(status().isOk());
        verify(mailSender, timeout(WAIT_MS)).sendAnswer(eq("commit@example.com"), anyString(), anyString(), anyString());

        verify(mailSender, never()).sendAnswer(eq("rollback@example.com"), anyString(), anyString(), anyString());
        assertThat(row(rolledBack).get("status")).isEqualTo("RECEIVED");
    }

    @Test
    @DisplayName("메일 발송이 실패해도 답변 API는 200이고 문의는 ANSWERED로 남는다")
    void mailFailureKeepsAnswer() throws Exception {
        long id = insert("me@example.com", "발송 실패");
        doThrow(new MailSendException("SMTP 오류")).when(mailSender)
                .sendAnswer(anyString(), anyString(), anyString(), anyString());

        answer(id, "답변", "내용")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ANSWERED"));

        verify(mailSender, timeout(WAIT_MS)).sendAnswer(anyString(), anyString(), anyString(), anyString());
        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("ANSWERED");
        assertThat(row.get("answer_title")).isEqualTo("답변");
    }

    @Test
    @DisplayName("문의 메일 전용 풀을 만들어도 Spring Boot 기본 applicationTaskExecutor가 남아 있다 (spring.task.execution.mode=force)")
    void keepsDefaultTaskExecutor() {
        assertThat(context.containsBean("applicationTaskExecutor"))
                .withFailMessage("applicationTaskExecutor가 없습니다. Executor 빈을 추가하면 Boot가 기본 실행기를 만들지 않으므로 "
                        + "application.yaml의 spring.task.execution.mode: force를 확인하세요.")
                .isTrue();
        ThreadPoolTaskExecutor supportMail = context.getBean(AsyncConfig.SUPPORT_MAIL_EXECUTOR, ThreadPoolTaskExecutor.class);
        assertThat(supportMail.getThreadNamePrefix()).isEqualTo("support-mail-");
        assertThat(supportMail.getMaxPoolSize()).isEqualTo(2);
        assertThat(supportMail.getQueueCapacity()).isEqualTo(100);
    }

    private ResultActions answer(long id, String title, String content) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/supports/{id}/answer", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("answerTitle", title, "answerContent", content))));
    }

    private long insert(String email, String title) {
        return repository.save(CustomerSupport.receive(null, email, TITLE_PREFIX + title, "내용입니다", null)).getId();
    }

    private Map<String, Object> row(long id) {
        return jdbcTemplate.queryForMap("SELECT * FROM customer_support WHERE id = ?", id);
    }
}
