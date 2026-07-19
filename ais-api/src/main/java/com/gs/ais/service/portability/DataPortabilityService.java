package com.gs.ais.service.portability;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gs.ais.config.StoragePaths;
import com.gs.ais.dto.request.DataExportRequest;
import com.gs.ais.dto.request.DataImportRequest;
import com.gs.ais.model.entity.ApiProvider;
import com.gs.ais.model.entity.Attachment;
import com.gs.ais.model.entity.Message;
import com.gs.ais.model.entity.ModelProvider;
import com.gs.ais.model.entity.Session;
import com.gs.ais.model.entity.SystemModelSettings;
import com.gs.ais.model.enums.MessageRole;
import com.gs.ais.model.enums.MessageStatus;
import com.gs.ais.model.enums.MessageType;
import com.gs.ais.model.enums.ProviderType;
import com.gs.ais.repository.ApiProviderRepository;
import com.gs.ais.repository.AttachmentRepository;
import com.gs.ais.repository.MessageRepository;
import com.gs.ais.repository.ModelProviderRepository;
import com.gs.ais.repository.SessionRepository;
import com.gs.ais.repository.SystemModelSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Service
public class DataPortabilityService {

    private static final Logger log = LoggerFactory.getLogger(DataPortabilityService.class);
    private static final String FORMAT_VERSION = "1";
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final AttachmentRepository attachmentRepository;
    private final ApiProviderRepository apiProviderRepository;
    private final ModelProviderRepository modelProviderRepository;
    private final SystemModelSettingsRepository systemModelSettingsRepository;
    private final ObjectMapper objectMapper;
    private final Path uploadRoot;

    public DataPortabilityService(SessionRepository sessionRepository,
                                  MessageRepository messageRepository,
                                  AttachmentRepository attachmentRepository,
                                  ApiProviderRepository apiProviderRepository,
                                  ModelProviderRepository modelProviderRepository,
                                  SystemModelSettingsRepository systemModelSettingsRepository,
                                  ObjectMapper objectMapper) {
        this(sessionRepository, messageRepository, attachmentRepository, apiProviderRepository,
                modelProviderRepository, systemModelSettingsRepository, objectMapper,
                Path.of("uploads").toAbsolutePath().normalize());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DataPortabilityService(SessionRepository sessionRepository,
                                  MessageRepository messageRepository,
                                  AttachmentRepository attachmentRepository,
                                  ApiProviderRepository apiProviderRepository,
                                  ModelProviderRepository modelProviderRepository,
                                  SystemModelSettingsRepository systemModelSettingsRepository,
                                  ObjectMapper objectMapper,
                                  StoragePaths storagePaths) {
        this(sessionRepository, messageRepository, attachmentRepository, apiProviderRepository,
                modelProviderRepository, systemModelSettingsRepository, objectMapper,
                storagePaths.uploadDir());
    }

    private DataPortabilityService(SessionRepository sessionRepository,
                                  MessageRepository messageRepository,
                                  AttachmentRepository attachmentRepository,
                                  ApiProviderRepository apiProviderRepository,
                                  ModelProviderRepository modelProviderRepository,
                                  SystemModelSettingsRepository systemModelSettingsRepository,
                                  ObjectMapper objectMapper,
                                  Path uploadRoot) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.apiProviderRepository = apiProviderRepository;
        this.modelProviderRepository = modelProviderRepository;
        this.systemModelSettingsRepository = systemModelSettingsRepository;
        this.uploadRoot = uploadRoot.toAbsolutePath().normalize();
        this.objectMapper = objectMapper.copy()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public Map<String, Object> preview() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessions", sessionRepository.count());
        body.put("messages", messageRepository.count());
        body.put("attachments", attachmentRepository.count());
        body.put("providers", apiProviderRepository.count());
        body.put("models", modelProviderRepository.count());
        body.put("settings", systemModelSettingsRepository.count());
        body.put("uploadRoot", uploadRoot.toAbsolutePath().toString());
        return body;
    }

    public ExportResult exportData(DataExportRequest request) throws IOException {
        Set<ExportSection> sections = resolveSections(request != null ? request.getSections() : null, true);
        boolean includeApiKeys = request != null && request.isIncludeApiKeys();
        List<Long> sessionIds = request != null ? request.getSessionIds() : null;

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Map<String, Object> counts = new LinkedHashMap<>();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            List<Session> sessions = List.of();
            List<Message> messages = List.of();
            List<Attachment> attachments = List.of();

            if (sections.contains(ExportSection.SESSIONS)) {
                sessions = loadSessions(sessionIds);
                messages = loadMessages(sessions);
                attachments = loadAttachments(messages);
                writeJson(zos, "data/sessions.json", sessions.stream().map(this::sessionDto).toList());
                writeJson(zos, "data/messages.json", messages.stream().map(this::messageDto).toList());
                writeJson(zos, "data/attachments.json", attachments.stream().map(this::attachmentDto).toList());
                counts.put("sessions", sessions.size());
                counts.put("messages", messages.size());
                counts.put("attachments", attachments.size());
            }

            Map<Long, Long> modelIdToExportId = new LinkedHashMap<>();
            if (sections.contains(ExportSection.PROVIDERS)) {
                List<ApiProvider> providers = apiProviderRepository.findAllByOrderByNameAsc();
                List<Map<String, Object>> providerDtos = new ArrayList<>();
                int modelCount = 0;
                for (ApiProvider provider : providers) {
                    List<ModelProvider> models = modelProviderRepository.findByApiProviderId(provider.getId());
                    provider.setModels(models);
                    Map<String, Object> dto = providerDto(provider, includeApiKeys);
                    modelCount += models.size();
                    for (ModelProvider model : models) {
                        if (model.getId() != null) {
                            modelIdToExportId.put(model.getId(), model.getId());
                        }
                    }
                    providerDtos.add(dto);
                }
                writeJson(zos, "data/providers.json", providerDtos);
                counts.put("providers", providerDtos.size());
                counts.put("models", modelCount);
            }

            if (sections.contains(ExportSection.SETTINGS)) {
                SystemModelSettings settings = systemModelSettingsRepository
                        .findById(SystemModelSettings.SINGLETON_ID)
                        .orElse(null);
                writeJson(zos, "data/system_settings.json", settingsDto(settings));
                counts.put("settings", settings == null ? 0 : 1);
            }

            int files = 0;
            if (sections.contains(ExportSection.FILES)) {
                Set<String> relativePaths = new LinkedHashSet<>();
                if (!sections.contains(ExportSection.SESSIONS)) {
                    // Still allow exporting all known files when sessions section omitted.
                    for (String url : messageRepository.findAllImageUrls()) {
                        toRelativeUploadPath(url).ifPresent(relativePaths::add);
                    }
                    for (String url : attachmentRepository.findAllFileUrls()) {
                        toRelativeUploadPath(url).ifPresent(relativePaths::add);
                    }
                } else {
                    for (Message message : messages) {
                        toRelativeUploadPath(message.getImageUrl()).ifPresent(relativePaths::add);
                    }
                    for (Attachment attachment : attachments) {
                        toRelativeUploadPath(attachment.getFileUrl()).ifPresent(relativePaths::add);
                    }
                }
                for (String relative : relativePaths) {
                    Path source = uploadRoot.resolve(relative).normalize();
                    if (!source.startsWith(uploadRoot.toAbsolutePath().normalize())
                            && !source.normalize().startsWith(uploadRoot.normalize())) {
                        continue;
                    }
                    if (!Files.isRegularFile(source)) {
                        continue;
                    }
                    zos.putNextEntry(new ZipEntry("files/" + relative.replace('\\', '/')));
                    Files.copy(source, zos);
                    zos.closeEntry();
                    files++;
                }
                counts.put("files", files);
            }

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("formatVersion", FORMAT_VERSION);
            manifest.put("createdAt", LocalDateTime.now().toString());
            manifest.put("sections", sections.stream().map(s -> s.name().toLowerCase(Locale.ROOT)).toList());
            manifest.put("includeApiKeys", includeApiKeys);
            manifest.put("counts", counts);
            writeJson(zos, "manifest.json", manifest);
        }

        String filename = "ais-export-" + LocalDateTime.now().format(FILE_TS) + ".zip";
        return new ExportResult(filename, bos.toByteArray());
    }

    @Transactional
    public Map<String, Object> importData(MultipartFile file, DataImportRequest request) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请上传导出 ZIP 文件");
        }
        String mode = request != null && StringUtils.hasText(request.getMode())
                ? request.getMode().trim().toLowerCase(Locale.ROOT)
                : "merge";
        if (!mode.equals("merge") && !mode.equals("replace")) {
            throw new IllegalArgumentException("导入模式仅支持 merge 或 replace");
        }
        boolean includeApiKeys = request == null || request.isIncludeApiKeys();

        Map<String, byte[]> entries = readZip(file.getInputStream());
        Map<String, Object> manifest = readJson(entries.get("manifest.json"), new TypeReference<>() {});
        Set<ExportSection> available = new LinkedHashSet<>();
        if (manifest != null && manifest.get("sections") instanceof Collection<?> collection) {
            for (Object item : collection) {
                available.add(ExportSection.from(String.valueOf(item)));
            }
        } else {
            if (entries.containsKey("data/sessions.json")) available.add(ExportSection.SESSIONS);
            if (entries.containsKey("data/providers.json")) available.add(ExportSection.PROVIDERS);
            if (entries.containsKey("data/system_settings.json")) available.add(ExportSection.SETTINGS);
            if (entries.keySet().stream().anyMatch(k -> k.startsWith("files/"))) available.add(ExportSection.FILES);
        }

        Set<ExportSection> selected = resolveSections(request != null ? request.getSections() : null, false);
        if (selected.isEmpty()) {
            selected = available;
        } else {
            selected.retainAll(available);
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("ZIP 中没有可导入的分区，或所选分区与包内容不匹配");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode);
        result.put("sections", selected.stream().map(s -> s.name().toLowerCase(Locale.ROOT)).toList());

        Map<Long, Long> providerModelIdMap = new HashMap<>();
        if (selected.contains(ExportSection.PROVIDERS)) {
            result.put("providers", importProviders(entries, mode, includeApiKeys, providerModelIdMap));
        }
        if (selected.contains(ExportSection.SETTINGS)) {
            result.put("settings", importSettings(entries, providerModelIdMap));
        }

        Map<Long, Long> sessionIdMap = new HashMap<>();
        Map<Long, Long> messageIdMap = new HashMap<>();
        if (selected.contains(ExportSection.SESSIONS)) {
            result.put("sessions", importSessions(entries, mode, sessionIdMap, messageIdMap, providerModelIdMap));
        }
        if (selected.contains(ExportSection.FILES) || selected.contains(ExportSection.SESSIONS)) {
            result.put("files", importFiles(entries));
        }
        log.info("Data import completed: {}", result);
        return result;
    }

    private Map<String, Object> importProviders(Map<String, byte[]> entries,
                                                String mode,
                                                boolean includeApiKeys,
                                                Map<Long, Long> modelIdMap) throws IOException {
        List<Map<String, Object>> providers = readJson(
                entries.get("data/providers.json"), new TypeReference<>() {});
        if (providers == null) {
            providers = List.of();
        }
        if ("replace".equals(mode)) {
            // Clear settings first to avoid dangling FK-like references.
            systemModelSettingsRepository.findById(SystemModelSettings.SINGLETON_ID).ifPresent(settings -> {
                settings.setDefaultChatModelId(null);
                settings.setDefaultImageModelId(null);
                systemModelSettingsRepository.save(settings);
            });
            modelProviderRepository.deleteAll();
            apiProviderRepository.deleteAll();
        }

        int upsertedProviders = 0;
        int upsertedModels = 0;
        for (Map<String, Object> dto : providers) {
            String providerKey = asString(dto.get("providerKey"));
            if (!StringUtils.hasText(providerKey)) {
                continue;
            }
            ApiProvider provider = apiProviderRepository.findByProviderKey(providerKey).orElseGet(ApiProvider::new);
            boolean isNew = provider.getId() == null;
            provider.setProviderKey(providerKey);
            provider.setName(defaultText(asString(dto.get("name")), providerKey));
            provider.setBaseUrl(defaultText(asString(dto.get("baseUrl")), "https://example.invalid/v1"));
            if (includeApiKeys || isNew || !StringUtils.hasText(provider.getApiKey())) {
                String apiKey = asString(dto.get("apiKey"));
                if (includeApiKeys || StringUtils.hasText(apiKey)) {
                    provider.setApiKey(apiKey);
                }
            }
            if (!isNew) {
                provider.setModels(new ArrayList<>(modelProviderRepository.findByApiProviderId(provider.getId())));
            } else if (provider.getModels() == null) {
                provider.setModels(new ArrayList<>());
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> models = (List<Map<String, Object>>) dto.get("models");
            Map<String, ModelProvider> existingByKey = new HashMap<>();
            for (ModelProvider model : provider.getModels()) {
                existingByKey.put(modelIdentity(model.getType(), model.getModelName()), model);
            }
            Set<String> seen = new LinkedHashSet<>();
            if (models != null) {
                for (Map<String, Object> modelDto : models) {
                    ProviderType type = ProviderType.valueOf(asString(modelDto.get("type")));
                    String modelName = asString(modelDto.get("modelName"));
                    if (!StringUtils.hasText(modelName)) {
                        continue;
                    }
                    String identity = modelIdentity(type, modelName);
                    seen.add(identity);
                    ModelProvider model = existingByKey.get(identity);
                    if (model == null) {
                        model = new ModelProvider();
                        provider.addModel(model);
                    }
                    applyModelDto(model, modelDto, provider);
                    Long oldId = asLong(modelDto.get("id"));
                    // ids assigned after save
                    modelDto.put("_oldId", oldId);
                    upsertedModels++;
                }
            }
            if ("replace".equals(mode)) {
                provider.getModels().removeIf(model -> !seen.contains(modelIdentity(model.getType(), model.getModelName())));
            }
            provider = apiProviderRepository.save(provider);
            upsertedProviders++;

            // Map old model ids after persistence.
            if (models != null) {
                Map<String, Long> newIds = new HashMap<>();
                for (ModelProvider model : provider.getModels()) {
                    newIds.put(modelIdentity(model.getType(), model.getModelName()), model.getId());
                }
                for (Map<String, Object> modelDto : models) {
                    Long oldId = asLong(modelDto.get("_oldId") != null ? modelDto.get("_oldId") : modelDto.get("id"));
                    ProviderType type = ProviderType.valueOf(asString(modelDto.get("type")));
                    String modelName = asString(modelDto.get("modelName"));
                    Long newId = newIds.get(modelIdentity(type, modelName));
                    if (oldId != null && newId != null) {
                        modelIdMap.put(oldId, newId);
                    }
                }
            }
        }
        return Map.of("providers", upsertedProviders, "models", upsertedModels);
    }

    private Map<String, Object> importSettings(Map<String, byte[]> entries,
                                               Map<Long, Long> modelIdMap) throws IOException {
        Map<String, Object> dto = readJson(entries.get("data/system_settings.json"), new TypeReference<>() {});
        if (dto == null) {
            return Map.of("updated", false);
        }
        SystemModelSettings settings = systemModelSettingsRepository
                .findById(SystemModelSettings.SINGLETON_ID)
                .orElseGet(() -> {
                    SystemModelSettings created = new SystemModelSettings();
                    created.setId(SystemModelSettings.SINGLETON_ID);
                    return created;
                });
        Long chatId = mapModelId(asLong(dto.get("defaultChatModelId")), modelIdMap);
        Long imageId = mapModelId(asLong(dto.get("defaultImageModelId")), modelIdMap);
        settings.setDefaultChatModelId(chatId);
        settings.setDefaultImageModelId(imageId);
        systemModelSettingsRepository.save(settings);
        return Map.of("updated", true, "defaultChatModelId", chatId, "defaultImageModelId", imageId);
    }

    private Map<String, Object> importSessions(Map<String, byte[]> entries,
                                               String mode,
                                               Map<Long, Long> sessionIdMap,
                                               Map<Long, Long> messageIdMap,
                                               Map<Long, Long> modelIdMap) throws IOException {
        if ("replace".equals(mode)) {
            attachmentRepository.deleteAll();
            messageRepository.deleteAll();
            sessionRepository.deleteAll();
        }

        List<Map<String, Object>> sessions = readJson(entries.get("data/sessions.json"), new TypeReference<>() {});
        List<Map<String, Object>> messages = readJson(entries.get("data/messages.json"), new TypeReference<>() {});
        List<Map<String, Object>> attachments = readJson(entries.get("data/attachments.json"), new TypeReference<>() {});
        if (sessions == null) sessions = List.of();
        if (messages == null) messages = List.of();
        if (attachments == null) attachments = List.of();

        int sessionCount = 0;
        for (Map<String, Object> dto : sessions) {
            Session session = new Session();
            session.setTitle(asString(dto.get("title")));
            session.setChatProviderId(mapModelId(asLong(dto.get("chatProviderId")), modelIdMap));
            session.setImageProviderId(mapModelId(asLong(dto.get("imageProviderId")), modelIdMap));
            session.setExternalChannel(asString(dto.get("externalChannel")));
            session.setExternalChatId(asString(dto.get("externalChatId")));
            Boolean autoTitle = asBoolean(dto.get("autoTitleEnabled"));
            session.setAutoTitleEnabled(autoTitle == null || autoTitle);
            if (dto.get("createdAt") != null) {
                session.setCreatedAt(LocalDateTime.parse(String.valueOf(dto.get("createdAt"))));
            }
            if (dto.get("updatedAt") != null) {
                session.setUpdatedAt(LocalDateTime.parse(String.valueOf(dto.get("updatedAt"))));
            }
            session = sessionRepository.save(session);
            Long oldId = asLong(dto.get("id"));
            if (oldId != null) {
                sessionIdMap.put(oldId, session.getId());
            }
            sessionCount++;
        }

        int messageCount = 0;
        // First pass without parent ids, second pass to patch parents.
        List<Message> savedMessages = new ArrayList<>();
        List<Long> oldParentIds = new ArrayList<>();
        for (Map<String, Object> dto : messages) {
            Long oldSessionId = asLong(dto.get("sessionId"));
            Long newSessionId = sessionIdMap.get(oldSessionId);
            if (newSessionId == null) {
                continue;
            }
            Session session = sessionRepository.findById(newSessionId).orElse(null);
            if (session == null) {
                continue;
            }
            Message message = new Message();
            message.setSession(session);
            message.setRole(MessageRole.valueOf(asString(dto.get("role"))));
            String type = asString(dto.get("messageType"));
            message.setMessageType(StringUtils.hasText(type) ? MessageType.valueOf(type) : MessageType.CHAT);
            String status = asString(dto.get("status"));
            message.setStatus(StringUtils.hasText(status) ? MessageStatus.valueOf(status) : MessageStatus.SUCCESS);
            message.setContent(asString(dto.get("content")));
            message.setErrorMessage(asString(dto.get("errorMessage")));
            message.setImageUrl(asString(dto.get("imageUrl")));
            message.setDrawPrompt(asString(dto.get("drawPrompt")));
            message.setDrawSize(asString(dto.get("drawSize")));
            message.setDrawQuality(asString(dto.get("drawQuality")));
            message.setDrawFormat(asString(dto.get("drawFormat")));
            message.setDrawProviderId(mapModelId(asLong(dto.get("drawProviderId")), modelIdMap));
            message.setPromptTokens(asInteger(dto.get("promptTokens")));
            message.setCompletionTokens(asInteger(dto.get("completionTokens")));
            message.setTotalTokens(asInteger(dto.get("totalTokens")));
            message.setEdited(Boolean.TRUE.equals(asBoolean(dto.get("edited"))));
            if (dto.get("createdAt") != null) {
                message.setCreatedAt(LocalDateTime.parse(String.valueOf(dto.get("createdAt"))));
            }
            message = messageRepository.save(message);
            Long oldId = asLong(dto.get("id"));
            if (oldId != null) {
                messageIdMap.put(oldId, message.getId());
            }
            oldParentIds.add(asLong(dto.get("parentMessageId")));
            savedMessages.add(message);
            messageCount++;
        }
        for (int i = 0; i < savedMessages.size(); i++) {
            Long oldParent = oldParentIds.get(i);
            if (oldParent == null) {
                continue;
            }
            Long newParent = messageIdMap.get(oldParent);
            if (newParent != null) {
                Message message = savedMessages.get(i);
                message.setParentMessageId(newParent);
                messageRepository.save(message);
            }
        }

        int attachmentCount = 0;
        for (Map<String, Object> dto : attachments) {
            Long oldMessageId = asLong(dto.get("messageId"));
            Long newMessageId = messageIdMap.get(oldMessageId);
            Message message = newMessageId == null ? null : messageRepository.findById(newMessageId).orElse(null);
            Attachment attachment = new Attachment();
            attachment.setMessage(message);
            attachment.setFilename(defaultText(asString(dto.get("filename")), UUID.randomUUID() + ".bin"));
            attachment.setOriginalName(defaultText(asString(dto.get("originalName")), attachment.getFilename()));
            attachment.setContentType(asString(dto.get("contentType")));
            attachment.setFileSize(asLong(dto.get("fileSize")));
            attachment.setFileUrl(asString(dto.get("fileUrl")));
            if (dto.get("createdAt") != null) {
                attachment.setCreatedAt(LocalDateTime.parse(String.valueOf(dto.get("createdAt"))));
            }
            attachmentRepository.save(attachment);
            attachmentCount++;
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sessions", sessionCount);
        summary.put("messages", messageCount);
        summary.put("attachments", attachmentCount);
        return summary;
    }

    private Map<String, Object> importFiles(Map<String, byte[]> entries) throws IOException {
        int copied = 0;
        Files.createDirectories(uploadRoot);
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey();
            if (!name.startsWith("files/") || name.endsWith("/")) {
                continue;
            }
            String relative = name.substring("files/".length());
            if (!StringUtils.hasText(relative) || relative.contains("..")) {
                continue;
            }
            Path target = uploadRoot.resolve(relative).normalize();
            Path root = uploadRoot.toAbsolutePath().normalize();
            if (!target.toAbsolutePath().normalize().startsWith(root)) {
                continue;
            }
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
            copied++;
        }
        return Map.of("copied", copied);
    }

    private List<Session> loadSessions(List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return sessionRepository.findAll();
        }
        return sessionRepository.findAllById(sessionIds);
    }

    private List<Message> loadMessages(List<Session> sessions) {
        if (sessions.isEmpty()) {
            return List.of();
        }
        List<Long> ids = sessions.stream().map(Session::getId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return messageRepository.findBySessionIdInOrderByCreatedAtAsc(ids);
    }

    private List<Attachment> loadAttachments(List<Message> messages) {
        if (messages.isEmpty()) {
            return List.of();
        }
        List<Attachment> all = new ArrayList<>();
        for (Message message : messages) {
            all.addAll(attachmentRepository.findByMessageId(message.getId()));
        }
        // Also include orphan attachments without message linkage if full export.
        return all;
    }

    private Map<String, Object> sessionDto(Session session) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", session.getId());
        dto.put("title", session.getTitle());
        dto.put("createdAt", session.getCreatedAt());
        dto.put("updatedAt", session.getUpdatedAt());
        dto.put("chatProviderId", session.getChatProviderId());
        dto.put("imageProviderId", session.getImageProviderId());
        dto.put("externalChannel", session.getExternalChannel());
        dto.put("externalChatId", session.getExternalChatId());
        dto.put("autoTitleEnabled", session.isAutoTitleEnabled());
        return dto;
    }

    private Map<String, Object> messageDto(Message message) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", message.getId());
        dto.put("sessionId", message.getSession() != null ? message.getSession().getId() : null);
        dto.put("role", message.getRole() != null ? message.getRole().name() : null);
        dto.put("messageType", message.getMessageType() != null ? message.getMessageType().name() : null);
        dto.put("status", message.getStatus() != null ? message.getStatus().name() : null);
        dto.put("content", message.getContent());
        dto.put("errorMessage", message.getErrorMessage());
        dto.put("imageUrl", message.getImageUrl());
        dto.put("drawPrompt", message.getDrawPrompt());
        dto.put("drawSize", message.getDrawSize());
        dto.put("drawQuality", message.getDrawQuality());
        dto.put("drawFormat", message.getDrawFormat());
        dto.put("drawProviderId", message.getDrawProviderId());
        dto.put("promptTokens", message.getPromptTokens());
        dto.put("completionTokens", message.getCompletionTokens());
        dto.put("totalTokens", message.getTotalTokens());
        dto.put("parentMessageId", message.getParentMessageId());
        dto.put("edited", message.isEdited());
        dto.put("createdAt", message.getCreatedAt());
        return dto;
    }

    private Map<String, Object> attachmentDto(Attachment attachment) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", attachment.getId());
        dto.put("messageId", attachment.getMessage() != null ? attachment.getMessage().getId() : null);
        dto.put("filename", attachment.getFilename());
        dto.put("originalName", attachment.getOriginalName());
        dto.put("contentType", attachment.getContentType());
        dto.put("fileSize", attachment.getFileSize());
        dto.put("fileUrl", attachment.getFileUrl());
        dto.put("createdAt", attachment.getCreatedAt());
        return dto;
    }

    private Map<String, Object> providerDto(ApiProvider provider, boolean includeApiKeys) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", provider.getId());
        dto.put("providerKey", provider.getProviderKey());
        dto.put("name", provider.getName());
        dto.put("baseUrl", provider.getBaseUrl());
        dto.put("apiKey", includeApiKeys ? provider.getApiKey() : maskSecret(provider.getApiKey()));
        List<Map<String, Object>> models = new ArrayList<>();
        if (provider.getModels() != null) {
            for (ModelProvider model : provider.getModels()) {
                models.add(modelDto(model));
            }
        }
        dto.put("models", models);
        return dto;
    }

    private Map<String, Object> modelDto(ModelProvider model) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", model.getId());
        dto.put("providerId", model.getLegacyProviderId());
        dto.put("name", model.getLegacyName());
        dto.put("type", model.getType() != null ? model.getType().name() : null);
        dto.put("modelName", model.getModelName());
        dto.put("baseUrl", model.getLegacyBaseUrl());
        dto.put("active", model.isActive());
        dto.put("systemPrompt", model.getSystemPrompt());
        dto.put("reasoningEffort", model.getReasoningEffort());
        dto.put("temperature", model.getTemperature());
        dto.put("timeoutSeconds", model.getTimeoutSeconds());
        dto.put("maxRetries", model.getMaxRetries());
        dto.put("retryBackoffSeconds", model.getRetryBackoffSeconds());
        dto.put("adapterType", model.getAdapterType());
        dto.put("configJson", model.getConfigJson());
        dto.put("supportsTextToImage", model.getSupportsTextToImage());
        dto.put("supportsImageToImage", model.getSupportsImageToImage());
        dto.put("priceCreditsMin", model.getPriceCreditsMin());
        dto.put("priceCreditsMax", model.getPriceCreditsMax());
        dto.put("priceCnyMin", model.getPriceCnyMin());
        dto.put("priceCnyMax", model.getPriceCnyMax());
        dto.put("priceDescription", model.getPriceDescription());
        return dto;
    }

    private Map<String, Object> settingsDto(SystemModelSettings settings) {
        Map<String, Object> dto = new LinkedHashMap<>();
        if (settings == null) {
            dto.put("defaultChatModelId", null);
            dto.put("defaultImageModelId", null);
            return dto;
        }
        dto.put("defaultChatModelId", settings.getDefaultChatModelId());
        dto.put("defaultImageModelId", settings.getDefaultImageModelId());
        dto.put("updatedAt", settings.getUpdatedAt());
        return dto;
    }

    private void applyModelDto(ModelProvider model, Map<String, Object> dto, ApiProvider provider) {
        model.setApiProvider(provider);
        model.setProviderId(defaultText(asString(dto.get("providerId")), provider.getProviderKey()));
        model.setName(defaultText(asString(dto.get("name")), provider.getName()));
        model.setType(ProviderType.valueOf(asString(dto.get("type"))));
        model.setModelName(asString(dto.get("modelName")));
        model.setBaseUrl(defaultText(asString(dto.get("baseUrl")), provider.getBaseUrl()));
        model.setActive(Boolean.TRUE.equals(asBoolean(dto.get("active"))));
        model.setSystemPrompt(asString(dto.get("systemPrompt")));
        model.setReasoningEffort(asString(dto.get("reasoningEffort")));
        model.setTemperature(asDouble(dto.get("temperature")));
        model.setTimeoutSeconds(asInteger(dto.get("timeoutSeconds")));
        model.setMaxRetries(asInteger(dto.get("maxRetries")));
        model.setRetryBackoffSeconds(asInteger(dto.get("retryBackoffSeconds")));
        model.setAdapterType(asString(dto.get("adapterType")));
        model.setConfigJson(asString(dto.get("configJson")));
        model.setSupportsTextToImage(asBoolean(dto.get("supportsTextToImage")));
        model.setSupportsImageToImage(asBoolean(dto.get("supportsImageToImage")));
        model.setPriceCreditsMin(asInteger(dto.get("priceCreditsMin")));
        model.setPriceCreditsMax(asInteger(dto.get("priceCreditsMax")));
        model.setPriceCnyMin(asBigDecimal(dto.get("priceCnyMin")));
        model.setPriceCnyMax(asBigDecimal(dto.get("priceCnyMax")));
        model.setPriceDescription(asString(dto.get("priceDescription")));
    }

    private Set<ExportSection> resolveSections(List<String> values, boolean defaultAll) {
        if (values == null || values.isEmpty()) {
            return defaultAll
                    ? Set.of(ExportSection.SESSIONS, ExportSection.PROVIDERS, ExportSection.SETTINGS, ExportSection.FILES)
                    : Set.of();
        }
        return ExportSection.parseAll(values);
    }

    private void writeJson(ZipOutputStream zos, String name, Object value) throws IOException {
        byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        zos.putNextEntry(new ZipEntry(name));
        zos.write(bytes);
        zos.closeEntry();
    }

    private Map<String, byte[]> readZip(InputStream inputStream) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                entries.put(entry.getName(), zis.readAllBytes());
            }
        }
        return entries;
    }

    private <T> T readJson(byte[] bytes, TypeReference<T> type) throws IOException {
        if (bytes == null) {
            return null;
        }
        return objectMapper.readValue(bytes, type);
    }

    private java.util.Optional<String> toRelativeUploadPath(String url) {
        if (!StringUtils.hasText(url)) {
            return java.util.Optional.empty();
        }
        String value = url.trim();
        if (value.startsWith("/api/images/")) {
            return java.util.Optional.of(value.substring("/api/images/".length()));
        }
        if (value.startsWith("/api/attachments/")) {
            return java.util.Optional.of("attachments/" + value.substring("/api/attachments/".length()));
        }
        return java.util.Optional.empty();
    }

    private static String maskSecret(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        if (value.length() <= 8) {
            return "********";
        }
        return value.substring(0, 4) + "********" + value.substring(value.length() - 4);
    }

    private static Long mapModelId(Long oldId, Map<Long, Long> map) {
        if (oldId == null) {
            return null;
        }
        return map.getOrDefault(oldId, oldId);
    }

    private static String modelIdentity(ProviderType type, String modelName) {
        return type.name() + "::" + modelName;
    }

    private static String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long asLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        String text = String.valueOf(value);
        if (!StringUtils.hasText(text) || "null".equals(text)) return null;
        return Long.parseLong(text);
    }

    private static Integer asInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.intValue();
        String text = String.valueOf(value);
        if (!StringUtils.hasText(text) || "null".equals(text)) return null;
        return Integer.parseInt(text);
    }

    private static Double asDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.doubleValue();
        String text = String.valueOf(value);
        if (!StringUtils.hasText(text) || "null".equals(text)) return null;
        return Double.parseDouble(text);
    }

    private static BigDecimal asBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal bigDecimal) return bigDecimal;
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
        String text = String.valueOf(value);
        if (!StringUtils.hasText(text) || "null".equals(text)) return null;
        return new BigDecimal(text);
    }

    private static Boolean asBoolean(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean bool) return bool;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    public record ExportResult(String filename, byte[] content) {
    }
}
