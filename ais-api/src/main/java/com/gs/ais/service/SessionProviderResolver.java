package com.gs.ais.service;

import com.gs.ais.model.entity.AppUser;
import com.gs.ais.model.entity.ModelProvider;
import com.gs.ais.model.entity.Session;
import com.gs.ais.model.enums.ProviderType;
import com.gs.ais.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「实际调用哪个模型」的**唯一解析链**。
 *
 * <p>顺序固定为：
 * <ol>
 *   <li><b>请求临时值</b>（本次发送 / 绘画 / 重发显式指定的 provider id）；</li>
 *   <li><b>会话值</b>（{@code session.chatProviderId} / {@code session.imageProviderId}）；</li>
 *   <li><b>该会话所属用户的默认值</b>（{@code session.userId → AppUser.defaultChatProviderId}
 *       / {@code defaultImageProviderId}）；</li>
 *   <li><b>系统启用项</b>（{@code SystemModelSettings} 里该类型的默认模型）。</li>
 * </ol>
 *
 * <p>每一环取不到（id 为 null）、provider 已被删除，或类型与本次调用不匹配时，
 * 都继续往下一环，而不是把请求打回失败。
 *
 * <p>第 3 环刻意**只用会话所属用户**（{@code session.userId}）解析，不读
 * {@code AuthContext}：重发 / 重新生成 / 队列异步执行时可能没有登录态，
 * 而显示端（前端下拉、占位气泡）本来就是按「会话 → 用户默认 → 系统启用」展示的。
 */
@Service
public class SessionProviderResolver {

    private static final Logger log = LoggerFactory.getLogger(SessionProviderResolver.class);

    private final ModelProviderService modelProviderService;
    private final AppUserRepository appUserRepository;

    public SessionProviderResolver(ModelProviderService modelProviderService,
                                   AppUserRepository appUserRepository) {
        this.modelProviderService = modelProviderService;
        this.appUserRepository = appUserRepository;
    }

    /** 对话模型：请求 → 会话 → 会话所属用户默认 → 系统启用。 */
    public ModelProvider resolveChat(Session session, Long requestProviderId) {
        return resolve(session, requestProviderId, ProviderType.CHAT);
    }

    /** 绘图模型：请求 → 会话 → 会话所属用户默认 → 系统启用。 */
    public ModelProvider resolveImage(Session session, Long requestProviderId) {
        return resolve(session, requestProviderId, ProviderType.IMAGE);
    }

    /**
     * 统一解析。最后一环（系统启用项）缺失时抛 {@code ProviderNotFoundException}，
     * 与改造前 chat 调用的失败语义保持一致。
     */
    @Transactional(readOnly = true)
    public ModelProvider resolve(Session session, Long requestProviderId, ProviderType type) {
        ModelProvider provider = resolveByChain(session, requestProviderId, type);
        return provider != null ? provider : modelProviderService.getActiveProvider(type);
    }

    /**
     * 同一条解析链，但最后一环缺失时返回 {@code null}。供计费兜底、异步队列等
     * 「拿不到也不能抛」的位置使用。
     */
    @Transactional(readOnly = true)
    public ModelProvider resolveOrNull(Session session, Long requestProviderId, ProviderType type) {
        ModelProvider provider = resolveByChain(session, requestProviderId, type);
        if (provider != null) {
            return provider;
        }
        try {
            return modelProviderService.getActiveProvider(type);
        } catch (RuntimeException e) {
            // 兜底行为保留（返回 null，不抛），但必须留下日志：否则「系统启用项
            // 缺失 / 配置损坏」会被悄悄吞掉，排查时看不出原因。
            log.warn("模型解析链兜底失败：解析不到可用模型，sessionId={}, expectedType={}",
                    session == null ? null : session.getId(), type, e);
            return null;
        }
    }

    /** 统一解析链的 id 形态，方便调用方把它交给队列 / 消息记录。 */
    @Transactional(readOnly = true)
    public Long resolveId(Session session, Long requestProviderId, ProviderType type) {
        ModelProvider provider = resolve(session, requestProviderId, type);
        return provider != null ? provider.getId() : null;
    }

    /** 前三环：请求临时值 → 会话值 → 会话所属用户默认值。 */
    private ModelProvider resolveByChain(Session session, Long requestProviderId, ProviderType type) {
        Long sessionId = session == null ? null : session.getId();
        ModelProvider provider = findUsableProvider(sessionId, requestProviderId, type);
        if (provider != null) {
            return provider;
        }
        provider = findUsableProvider(sessionId, sessionProviderId(session, type), type);
        if (provider != null) {
            return provider;
        }
        return findUsableProvider(sessionId, ownerDefaultProviderId(session, type), type);
    }

    private Long sessionProviderId(Session session, ProviderType type) {
        if (session == null) {
            return null;
        }
        return type == ProviderType.CHAT ? session.getChatProviderId() : session.getImageProviderId();
    }

    /**
     * 会话所属用户的默认模型。只认同一条会话归属链：{@code session.userId → AppUser}，
     * 与「谁登录」无关，因此异步 / 队列执行也能得到与界面一致的答案。
     */
    private Long ownerDefaultProviderId(Session session, ProviderType type) {
        if (session == null || session.getUserId() == null) {
            return null;
        }
        return appUserRepository.findById(session.getUserId())
                .map(user -> defaultProviderId(user, type))
                .orElse(null);
    }

    private static Long defaultProviderId(AppUser user, ProviderType type) {
        return type == ProviderType.CHAT ? user.getDefaultChatProviderId() : user.getDefaultImageProviderId();
    }

    /**
     * 按 id 取一个**可用**的 provider：不存在（已删除）或类型不符都算不可用，
     * 由调用方继续解析链的下一环。
     */
    private ModelProvider findUsableProvider(Long sessionId, Long providerId, ProviderType type) {
        if (providerId == null) {
            return null;
        }
        try {
            ModelProvider provider = modelProviderService.getById(providerId);
            return provider != null && provider.getType() == type ? provider : null;
        } catch (RuntimeException e) {
            // 该环不可用（已删除 / 配置损坏）→ 继续解析下一环；保留 debug 日志说明
            // 跳过了哪一环，避免配置错误被完全静默掩盖。
            log.debug("模型解析链跳过不可用的一环：sessionId={}, providerId={}, expectedType={}",
                    sessionId, providerId, type, e);
            return null;
        }
    }
}
