package com.gs.ais.integration;

import com.gs.ais.model.entity.Message;
import com.gs.ais.model.entity.Session;
import com.gs.ais.model.enums.MessageRole;
import com.gs.ais.model.enums.MessageStatus;
import com.gs.ais.model.enums.MessageType;
import com.gs.ais.repository.FavoriteRepository;
import com.gs.ais.repository.MessageRepository;
import com.gs.ais.repository.SessionRepository;
import com.gs.ais.service.FavoriteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Problem 4.4: saving the same message twice — sequentially or concurrently —
 * must be idempotent and must never surface a uniqueness error.
 *
 * <p>Intentionally not {@code @Transactional}: the concurrent workers each need
 * their own transaction, so the fixture rows are committed and removed explicitly.
 */
@SpringBootTest(properties = "feishu.enabled=false")
class FavoriteConcurrencyIntegrationTests {

    @Autowired
    private FavoriteService favoriteService;

    @Autowired
    private FavoriteRepository favoriteRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private SessionRepository sessionRepository;

    @Test
    void concurrentAndRepeatedSavesOfTheSameMessageStayIdempotent() throws Exception {
        Session session = new Session();
        session.setUserId(1L);
        session = sessionRepository.saveAndFlush(session);

        Message message = new Message();
        message.setSession(session);
        message.setRole(MessageRole.ASSISTANT);
        message.setMessageType(MessageType.DRAW_RESPONSE);
        message.setStatus(MessageStatus.SUCCESS);
        message.setImageUrl("/api/images/generated/concurrent.png");
        message = messageRepository.saveAndFlush(message);

        Long sessionId = session.getId();
        Long messageId = message.getId();
        try {
            int threads = 4;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return favoriteService.add(1L, false, messageId).getId();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "workers did not become ready");
            start.countDown();

            Set<Long> ids = new HashSet<>();
            for (Future<Long> future : futures) {
                // Any uniqueness failure would surface here as an ExecutionException.
                ids.add(future.get(30, TimeUnit.SECONDS));
            }
            pool.shutdownNow();

            assertEquals(1, ids.size(), "concurrent saves must all resolve to a single snapshot");
            assertEquals(1, favoriteRepository.findAllByMessageId(messageId).size());

            // A later repeat is still the same row.
            assertEquals(ids.iterator().next(), favoriteService.add(1L, false, messageId).getId());
            assertEquals(1, favoriteRepository.findAllByMessageId(messageId).size());
        } finally {
            favoriteRepository.deleteAll(favoriteRepository.findAllByMessageId(messageId));
            messageRepository.deleteById(messageId);
            sessionRepository.deleteById(sessionId);
        }
    }
}
