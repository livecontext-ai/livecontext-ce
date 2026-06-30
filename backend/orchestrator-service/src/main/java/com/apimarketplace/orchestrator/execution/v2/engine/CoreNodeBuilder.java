package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.execution.v2.nodes.*;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Creates control flow nodes from Core definitions in WorkflowPlan.
 * Handles decision, fork, merge, wait, and transform nodes.
 *
 * Uses a two-pass approach:
 * - Pass 1: Create nodes WITHOUT targets (to ensure all nodes exist first)
 * - Pass 2: Wire targets (done by EdgeWiringOrchestrator)
 */
@Component
public class CoreNodeBuilder {

    private static final Logger logger = LoggerFactory.getLogger(CoreNodeBuilder.class);

    private final TemplateEngine templateEngine;

    public CoreNodeBuilder(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    /**
     * Creates all core nodes from the plan (Pass 1 - without targets).
     * Targets are wired in Pass 2 by EdgeWiringOrchestrator.
     *
     * @param nodeMap The map to populate with created nodes
     * @param plan The workflow plan containing core definitions
     * @param mergeSourceNodes Map of merge keys to their source node keys (from edges)
     */
    public void createCoreNodes(
            Map<String, ExecutionNode> nodeMap,
            WorkflowPlan plan,
            Map<String, List<String>> mergeSourceNodes) {

        createDecisionNodes(nodeMap, plan);
        createSwitchNodes(nodeMap, plan);
        createForkNodes(nodeMap, plan);
        createMergeNodes(nodeMap, plan, mergeSourceNodes);
        createAggregateNodes(nodeMap, plan);
        createWaitNodes(nodeMap, plan);
        createTransformNodes(nodeMap, plan);
        createDownloadFileNodes(nodeMap, plan);
        createExitNodes(nodeMap, plan);
        createEndNodes(nodeMap, plan);
        createResponseNodes(nodeMap, plan);
        createOptionNodes(nodeMap, plan);
        createHttpRequestNodes(nodeMap, plan);
        createApprovalNodes(nodeMap, plan);
        createDataInputNodes(nodeMap, plan);
        createLoopNodes(nodeMap, plan);
        createFilterNodes(nodeMap, plan);
        createSortNodes(nodeMap, plan);
        createLimitNodes(nodeMap, plan);
        createRemoveDuplicatesNodes(nodeMap, plan);
        createSummarizeNodes(nodeMap, plan);
        createDateTimeNodes(nodeMap, plan);
        createCryptoJwtNodes(nodeMap, plan);
        createXmlNodes(nodeMap, plan);
        createCompressionNodes(nodeMap, plan);
        createRssNodes(nodeMap, plan);
        createConvertToFileNodes(nodeMap, plan);
        createExtractFromFileNodes(nodeMap, plan);
        createCompareDatasetsNodes(nodeMap, plan);
        createSubWorkflowNodes(nodeMap, plan);
        createRespondToWebhookNodes(nodeMap, plan);
        createSendEmailNodes(nodeMap, plan);
        createEmailInboxNodes(nodeMap, plan);
        createCodeNodes(nodeMap, plan);
        createSetNodes(nodeMap, plan);
        createHtmlExtractNodes(nodeMap, plan);
        createTaskNodes(nodeMap, plan);
        createStopOnErrorNodes(nodeMap, plan);
        createSshNodes(nodeMap, plan);
        createSftpNodes(nodeMap, plan);
        createDatabaseNodes(nodeMap, plan);
    }

    /**
     * Creates Set nodes from Core definitions.
     * Set nodes assign or transform fields on the input data (Set / Edit Fields).
     */
    public void createSetNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }
        for (Core coreNode : plan.getCores()) {
            if (!"set".equals(coreNode.type())) {
                continue;
            }
            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String setKey = "core:" + normalizedLabel;
            if (nodeMap.containsKey(setKey)) {
                continue;
            }
            Core.SetConfig config = coreNode.setConfig();
            SetNode setNode = SetNode.builder()
                .nodeId(setKey)
                .setConfig(config)
                .templateAdapter(null)
                .build();
            nodeMap.put(setKey, setNode);
            logger.info("Created set node: {} (assignments={})",
                setKey, config != null ? config.assignments().size() : 0);
        }
    }

    /**
     * Creates HtmlExtract nodes from Core definitions.
     * HtmlExtract nodes parse HTML using CSS selectors via jsoup.
     */
    public void createHtmlExtractNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }
        for (Core coreNode : plan.getCores()) {
            if (!"html_extract".equals(coreNode.type())) {
                continue;
            }
            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String key = "core:" + normalizedLabel;
            if (nodeMap.containsKey(key)) {
                continue;
            }
            Core.HtmlExtractConfig config = coreNode.htmlExtractConfig();
            HtmlExtractNode htmlExtractNode = HtmlExtractNode.builder()
                .nodeId(key)
                .htmlExtractConfig(config)
                .templateAdapter(null)
                .build();
            nodeMap.put(key, htmlExtractNode);
            logger.info("Created html_extract node: {} (mode={}, fields={})",
                key,
                config != null ? config.extractionMode() : "single",
                config != null ? config.fields().size() : 0);
        }
    }

    /**
     * Creates Task nodes from Core definitions.
     * Task nodes perform CRUD operations on agent tasks via AgentClient.
     */
    public void createTaskNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }
        for (Core coreNode : plan.getCores()) {
            if (!"task".equals(coreNode.type())) {
                continue;
            }
            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String key = "core:" + normalizedLabel;
            if (nodeMap.containsKey(key)) {
                continue;
            }
            Core.TaskConfig config = coreNode.taskConfig();
            TaskNode taskNode = TaskNode.builder()
                .nodeId(key)
                .taskConfig(config)
                .templateAdapter(null)
                .build();
            nodeMap.put(key, taskNode);
            logger.info("Created task node: {} (operation={})",
                key, config != null ? config.operation() : "list_tasks");
        }
    }

    /**
     * Creates StopOnError nodes from Core definitions.
     * StopOnError nodes immediately fail the workflow with an error message.
     */
    public void createStopOnErrorNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }
        for (Core coreNode : plan.getCores()) {
            if (!"stop_on_error".equals(coreNode.type())) {
                continue;
            }
            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String key = "core:" + normalizedLabel;
            if (nodeMap.containsKey(key)) {
                continue;
            }
            Core.StopOnErrorConfig config = coreNode.stopOnErrorConfig();
            StopOnErrorNode node = StopOnErrorNode.builder()
                .nodeId(key)
                .stopOnErrorConfig(config)
                .templateAdapter(null)
                .build();
            nodeMap.put(key, node);
            logger.info("Created stop_on_error node: {} (errorMessage={})",
                key, config != null ? config.errorMessage() : "default");
        }
    }

    /**
     * Creates SSH nodes from Core definitions.
     * SSH nodes execute commands on remote servers via SSH.
     */
    public void createSshNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }
        for (Core coreNode : plan.getCores()) {
            if (!"ssh".equals(coreNode.type())) {
                continue;
            }
            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String key = "core:" + normalizedLabel;
            if (nodeMap.containsKey(key)) {
                continue;
            }
            Core.SshConfig config = coreNode.sshConfig();
            SshNode node = SshNode.builder()
                .nodeId(key)
                .sshConfig(config)
                .templateAdapter(null)
                .build();
            nodeMap.put(key, node);
            logger.info("Created ssh node: {} (host={})",
                key, config != null ? config.host() : "none");
        }
    }

    /**
     * Creates SFTP nodes from Core definitions.
     * SFTP nodes perform file operations on remote servers via SFTP.
     */
    public void createSftpNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }
        for (Core coreNode : plan.getCores()) {
            if (!"sftp".equals(coreNode.type())) {
                continue;
            }
            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String key = "core:" + normalizedLabel;
            if (nodeMap.containsKey(key)) {
                continue;
            }
            Core.SftpConfig config = coreNode.sftpConfig();
            SftpNode node = SftpNode.builder()
                .nodeId(key)
                .sftpConfig(config)
                .templateAdapter(null)
                .build();
            nodeMap.put(key, node);
            logger.info("Created sftp node: {} (operation={})",
                key, config != null ? config.operation() : "list");
        }
    }

    /**
     * Creates Database nodes from Core definitions.
     * Database nodes execute SQL queries against databases.
     */
    public void createDatabaseNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }
        for (Core coreNode : plan.getCores()) {
            if (!"database".equals(coreNode.type())) {
                continue;
            }
            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String key = "core:" + normalizedLabel;
            if (nodeMap.containsKey(key)) {
                continue;
            }
            Core.DatabaseConfig config = coreNode.databaseConfig();
            DatabaseNode node = DatabaseNode.builder()
                .nodeId(key)
                .databaseConfig(config)
                .templateAdapter(null)
                .build();
            nodeMap.put(key, node);
            logger.info("Created database node: {} (operation={})",
                key, config != null ? config.operation() : "select");
        }
    }

    /**
     * Creates decision nodes from Core definitions WITHOUT targets.
     * Targets will be wired in a second pass once all nodes exist.
     */
    public void createDecisionNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"decision".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String decisionKey = "core:" + normalizedLabel;

            if (nodeMap.containsKey(decisionKey)) {
                continue; // Already created
            }

            // Build DecisionNode from Core conditions with EMPTY targets
            DecisionNode.Builder builder = DecisionNode.builder()
                .nodeId(decisionKey)
                .templateEngine(templateEngine);

            // Add branches from decisionConditions (targets added later)
            if (coreNode.decisionConditions() != null) {
                boolean isFirst = true;
                for (Core.DecisionCondition dc : coreNode.decisionConditions()) {
                    String condition = dc.expression();
                    if ("else".equals(dc.type())) {
                        builder.elseBranch(new ArrayList<>());
                    } else if (isFirst) {
                        builder.ifBranch(condition, new ArrayList<>());
                        isFirst = false;
                    } else {
                        builder.elsifBranch(condition, new ArrayList<>());
                    }
                }
            }

            DecisionNode decisionNode = builder.build();
            nodeMap.put(decisionKey, decisionNode);
            logger.info("🔀 Created decision node (pass 1): {}", decisionKey);
        }
    }

    /**
     * Creates switch nodes from Core definitions WITHOUT targets.
     * Targets will be wired in a second pass by SwitchNodeWirer.
     */
    public void createSwitchNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"switch".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String switchKey = "core:" + normalizedLabel;

            if (nodeMap.containsKey(switchKey)) {
                continue; // Already created
            }

            // Build SwitchNode from Core switchCases with EMPTY targets
            SwitchNode.Builder builder = SwitchNode.builder()
                .nodeId(switchKey)
                .switchExpression(coreNode.switchExpression())
                .templateEngine(templateEngine);

            // Add cases from switchCases (targets added later by SwitchNodeWirer)
            if (coreNode.switchCases() != null) {
                for (Core.SwitchCase sc : coreNode.switchCases()) {
                    if ("default".equals(sc.type())) {
                        builder.addDefault(sc.label());
                    } else {
                        builder.addCase(sc.value(), sc.label());
                    }
                }
            }

            SwitchNode switchNode = builder.build();
            nodeMap.put(switchKey, switchNode);
            logger.info("🔀 Created switch node (pass 1): {} (cases={})",
                switchKey, coreNode.switchCases() != null ? coreNode.switchCases().size() : 0);
        }
    }

    /**
     * Creates fork nodes from Core definitions.
     * Fork nodes activate ALL their branches in parallel (unlike Decision which selects one).
     * Branch targets are wired in a second pass.
     */
    public void createForkNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"fork".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String forkKey = "core:" + normalizedLabel;

            if (nodeMap.containsKey(forkKey)) {
                continue; // Already created
            }

            // Create ForkNode (branches will be wired in second pass)
            ForkNode forkNode = ForkNode.builder()
                .nodeId(forkKey)
                .build();

            nodeMap.put(forkKey, forkNode);
            logger.info("🔱 Created fork node: {}", forkKey);
        }
    }

    /**
     * Creates merge nodes from Core definitions.
     * Merge nodes wait for ALL their source branches to complete.
     * Source nodes are collected from edges pointing TO the merge node.
     */
    public void createMergeNodes(
            Map<String, ExecutionNode> nodeMap,
            WorkflowPlan plan,
            Map<String, List<String>> mergeSourceNodes) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"merge".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String mergeKey = "core:" + normalizedLabel;

            if (nodeMap.containsKey(mergeKey)) {
                continue; // Already created
            }

            // Get source node IDs from edges (nodes that feed into this merge)
            List<String> sourceNodeIds = mergeSourceNodes.getOrDefault(mergeKey, List.of());

            // Create MergeNode with source nodes
            // Using default Queue1To1Strategy (wait for ALL sources)
            // Note: Use fully qualified name to avoid conflict with domain.workflow.MergeNode
            com.apimarketplace.orchestrator.execution.v2.nodes.MergeNode mergeNode =
                com.apimarketplace.orchestrator.execution.v2.nodes.MergeNode.builder()
                    .nodeId(mergeKey)
                    .sourceNodeIds(sourceNodeIds)
                    .build();

            nodeMap.put(mergeKey, mergeNode);
            logger.info("⚙️ Created merge node: {} (sources={})", mergeKey, sourceNodeIds);
        }
    }

    /**
     * Creates aggregate nodes from Core definitions.
     * Aggregate nodes collect N items into 1 (data transformation).
     * Unlike Merge (which synchronizes streams/branches), Aggregate transforms DATA.
     */
    public void createAggregateNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"aggregate".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String aggregateKey = "core:" + normalizedLabel;

            if (nodeMap.containsKey(aggregateKey)) {
                continue; // Already created
            }

            // Build AggregateNode from Core with fields
            AggregateNode.Builder builder = AggregateNode.builder()
                .nodeId(aggregateKey)
                .templateEngine(templateEngine);

            // Add fields from aggregateConfig
            if (coreNode.aggregateConfig() != null && coreNode.aggregateConfig().fields() != null) {
                for (Core.AggregateField field : coreNode.aggregateConfig().fields()) {
                    builder.addField(field.label(), field.expression());
                }
            }

            AggregateNode aggregateNode = builder.build();
            nodeMap.put(aggregateKey, aggregateNode);
            logger.info("📊 Created aggregate node: {}", aggregateKey);
        }
    }

    /**
     * Creates wait nodes from Core definitions.
     * Wait nodes pause execution for a specified duration.
     */
    public void createWaitNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"wait".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String waitKey = "core:" + normalizedLabel;

            if (nodeMap.containsKey(waitKey)) {
                continue; // Already created
            }

            // Get duration from wait config
            long durationMs = 0;
            if (coreNode.waitConfig() != null) {
                durationMs = coreNode.waitConfig().duration();
            }

            // Create WaitNode
            WaitNode waitNode = WaitNode.builder()
                .nodeId(waitKey)
                .durationMs(durationMs)
                .build();

            nodeMap.put(waitKey, waitNode);
            logger.info("⏳ Created wait node: {} (durationMs={})", waitKey, durationMs);
        }
    }

    /**
     * Creates transform nodes from Core definitions.
     * Transform nodes apply data mappings/transformations.
     */
    public void createTransformNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"transform".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String transformKey = "core:" + normalizedLabel;

            if (nodeMap.containsKey(transformKey)) {
                continue; // Already created
            }

            // Get mappings from transform config
            List<Core.TransformMapping> mappings = List.of();
            if (coreNode.transformConfig() != null && coreNode.transformConfig().mappings() != null) {
                mappings = coreNode.transformConfig().mappings();
            }

            // Create TransformNode
            TransformNode transformNode = TransformNode.builder()
                .nodeId(transformKey)
                .mappings(mappings)
                .build();

            nodeMap.put(transformKey, transformNode);
            logger.info("🔄 Created transform node: {} (mappingCount={})", transformKey, mappings.size());
        }
    }

    /**
     * Creates download file nodes from Core definitions.
     * Download file nodes fetch files from URLs and store them in S3/MinIO.
     */
    public void createDownloadFileNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"download_file".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String downloadKey = "core:" + normalizedLabel;

            if (nodeMap.containsKey(downloadKey)) {
                continue; // Already created
            }

            // Get config from download config
            String urlExpression = null;
            String filenameExpression = null;
            String mimeTypeExpression = null;

            if (coreNode.downloadConfig() != null) {
                urlExpression = coreNode.downloadConfig().url();
                filenameExpression = coreNode.downloadConfig().filename();
                mimeTypeExpression = coreNode.downloadConfig().mimeType();
            }

            // Create DownloadFileNode
            DownloadFileNode downloadNode = DownloadFileNode.builder()
                .nodeId(downloadKey)
                .urlExpression(urlExpression)
                .filenameExpression(filenameExpression)
                .mimeTypeExpression(mimeTypeExpression)
                .build();

            nodeMap.put(downloadKey, downloadNode);
            logger.info("📥 Created download file node: {} (url={})", downloadKey, urlExpression);
        }
    }

    /**
     * Creates exit nodes from Core definitions.
     * Exit nodes end execution along their branch (other parallel branches continue).
     */
    public void createExitNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"exit".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String exitKey = "core:" + normalizedLabel;

            if (nodeMap.containsKey(exitKey)) {
                continue; // Already created
            }

            // Create ExitNode
            ExitNode exitNode = ExitNode.builder()
                .nodeId(exitKey)
                .reason("Branch exited at: " + label)
                .build();

            nodeMap.put(exitKey, exitNode);
            logger.info("🚪 Created exit node: {}", exitKey);
        }
    }

    /**
     * Creates end nodes from Core definitions.
     * End nodes mark workflow completion as terminal nodes (no successors).
     */
    public void createEndNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"end".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String endKey = "core:" + normalizedLabel;

            if (nodeMap.containsKey(endKey)) {
                continue; // Already created
            }

            // Create EndNode
            EndNode endNode = new EndNode(endKey);
            nodeMap.put(endKey, endNode);
            logger.info("🏁 Created end node: {}", endKey);
        }
    }

    /**
     * Creates response nodes from Core definitions.
     * Response nodes send a message to chat and continue execution.
     */
    public void createResponseNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"response".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String responseKey = "core:" + normalizedLabel;

            if (nodeMap.containsKey(responseKey)) {
                continue; // Already created
            }

            // Get message from response config
            String messageTemplate = "";
            if (coreNode.responseConfig() != null && coreNode.responseConfig().message() != null) {
                messageTemplate = coreNode.responseConfig().message();
            }

            // Create ResponseNode
            ResponseNode responseNode = ResponseNode.builder()
                .nodeId(responseKey)
                .messageTemplate(messageTemplate)
                .build();

            nodeMap.put(responseKey, responseNode);
            logger.info("💬 Created response node: {}", responseKey);
        }
    }

    /**
     * Creates option nodes from Core definitions.
     * Option nodes evaluate expressions for each choice - first true wins.
     * Similar to decision nodes but with N choices instead of if/elseif/else.
     */
    public void createOptionNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"option".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String optionKey = "core:" + normalizedLabel;

            if (nodeMap.containsKey(optionKey)) {
                continue; // Already created
            }

            // Build OptionNode from Core choices with EMPTY targets
            OptionNode.Builder builder = OptionNode.builder()
                .nodeId(optionKey)
                .templateEngine(templateEngine);

            // Add choices from optionChoices (targets added later)
            if (coreNode.optionChoices() != null) {
                for (Core.OptionChoice choice : coreNode.optionChoices()) {
                    builder.addChoice(
                        choice.id(),
                        choice.label(),
                        choice.expression(),
                        new ArrayList<>()
                    );
                }
            }

            OptionNode optionNode = builder.build();
            nodeMap.put(optionKey, optionNode);
            logger.info("🔘 Created option node (pass 1): {}", optionKey);
        }
    }

    /**
     * Creates HTTP request nodes from Core definitions.
     * HTTP request nodes make HTTP calls to external APIs.
     */
    public void createHttpRequestNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"http_request".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String httpRequestKey = "core:" + normalizedLabel;

            if (nodeMap.containsKey(httpRequestKey)) {
                continue; // Already created
            }

            // Get config from httpRequestConfig
            Core.HttpRequestConfig config = coreNode.httpRequestConfig();

            HttpRequestNode.Builder builder = HttpRequestNode.builder()
                .nodeId(httpRequestKey);

            if (config != null) {
                builder.method(config.method())
                    .urlExpression(config.url())
                    .authType(config.authType())
                    .authConfig(config.authConfig())
                    .queryParams(config.queryParams())
                    .headers(config.headers())
                    .bodyType(config.bodyType())
                    .bodyExpression(config.body())
                    .contentType(config.contentType())
                    .timeout(config.timeout());
            }

            HttpRequestNode httpRequestNode = builder.build();
            nodeMap.put(httpRequestKey, httpRequestNode);
            logger.info("🌐 Created HTTP request node: {} (method={}, url={})",
                httpRequestKey,
                config != null ? config.method() : "GET",
                config != null ? config.url() : "null");
        }
    }

    /**
     * Creates approval nodes from Core definitions.
     * Approval nodes are branching nodes with ports: approved, rejected, timeout.
     * Port targets are wired in a second pass by ApprovalNodeWirer.
     */
    public void createApprovalNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"approval".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String approvalKey = "core:" + normalizedLabel;

            if (nodeMap.containsKey(approvalKey)) {
                continue; // Already created
            }

            // Get config from approvalConfig
            java.util.List<String> approverRoles = java.util.List.of();
            int requiredApprovals = 1;
            long timeoutMs = 86400000L; // 24h default
            String contextTemplate = "";

            if (coreNode.approvalConfig() != null) {
                Core.ApprovalConfig config = coreNode.approvalConfig();
                approverRoles = config.approverRoles();
                requiredApprovals = config.requiredApprovals();
                timeoutMs = config.timeoutMs();
                contextTemplate = config.contextTemplate();
            }

            // Create UserApprovalNode (port targets wired in second pass)
            UserApprovalNode approvalNode = UserApprovalNode.builder()
                .nodeId(approvalKey)
                .approverRoles(approverRoles)
                .requiredApprovals(requiredApprovals)
                .timeoutMs(timeoutMs)
                .contextTemplate(contextTemplate)
                .build();

            nodeMap.put(approvalKey, approvalNode);
            logger.info("Created approval node (pass 1): {} (requiredApprovals={}, timeoutMs={})",
                approvalKey, requiredApprovals, timeoutMs);
        }
    }

    /**
     * Creates data input nodes from Core definitions.
     * Data input nodes provide multiple labeled text and/or file inputs.
     */
    public void createDataInputNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"data_input".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String dataInputKey = "core:" + normalizedLabel;

            if (nodeMap.containsKey(dataInputKey)) {
                continue;
            }

            List<Core.DataInputItem> items = List.of();
            if (coreNode.dataInputConfig() != null) {
                items = coreNode.dataInputConfig().items();
            }

            DataInputNode node = DataInputNode.builder()
                .nodeId(dataInputKey)
                .items(items)
                .build();

            nodeMap.put(dataInputKey, node);
            logger.info("Created data input node: {} (items={})", dataInputKey, items.size());
        }
    }

    /**
     * Creates loop nodes from Core definitions WITHOUT targets.
     * Loop nodes evaluate a condition and route to body (if true) or exit (if false).
     * Body/exit targets are wired in a second pass by LoopNodeWirer.
     */
    public void createLoopNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"loop".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String loopKey = "core:" + normalizedLabel;

            if (nodeMap.containsKey(loopKey)) {
                continue; // Already created
            }

            String loopCondition = coreNode.loopCondition();
            int maxIterations = coreNode.maxIterations() != null ? coreNode.maxIterations() : 10;

            LoopNode loopNode = LoopNode.builder()
                .nodeId(loopKey)
                .loopCondition(loopCondition)
                .maxIterations(maxIterations)
                .templateEngine(templateEngine)
                .build();

            nodeMap.put(loopKey, loopNode);
            logger.info("Created loop node (pass 1): {} (condition={}, maxIterations={})",
                loopKey, loopCondition, maxIterations);
        }
    }

    /**
     * Creates filter nodes from Core definitions.
     * Filter nodes keep only items matching conditions.
     */
    public void createFilterNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"filter".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String filterKey = "core:" + normalizedLabel;

            if (nodeMap.containsKey(filterKey)) {
                continue;
            }

            Core.FilterConfig config = coreNode.filterConfig();
            String inputExpr = (config != null && config.input() != null) ? config.input()
                : (coreNode.params() != null ? (String) coreNode.params().get("input") : null);
            FilterNode filterNode = FilterNode.builder()
                .nodeId(filterKey)
                .filterConfig(config)
                .inputExpression(inputExpr)
                .templateAdapter(null) // Injected via acceptServices
                .build();

            nodeMap.put(filterKey, filterNode);
            logger.info("🔍 Created filter node: {} (conditions={})",
                filterKey, config != null ? config.conditions().size() : 0);
        }
    }

    /**
     * Creates sort nodes from Core definitions.
     * Sort nodes reorder items by one or more fields.
     */
    public void createSortNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"sort".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String sortKey = "core:" + normalizedLabel;

            if (nodeMap.containsKey(sortKey)) {
                continue;
            }

            Core.SortConfig config = coreNode.sortConfig();
            String inputExpr = (config != null && config.input() != null) ? config.input()
                : (coreNode.params() != null ? (String) coreNode.params().get("input") : null);
            SortNode sortNode = SortNode.builder()
                .nodeId(sortKey)
                .sortConfig(config)
                .inputExpression(inputExpr)
                .templateAdapter(null)
                .build();

            nodeMap.put(sortKey, sortNode);
            logger.info("🔃 Created sort node: {} (fields={})",
                sortKey, config != null ? config.fields().size() : 0);
        }
    }

    /**
     * Creates limit nodes from Core definitions.
     * Limit nodes pass through only first/last N items.
     */
    public void createLimitNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"limit".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String limitKey = "core:" + normalizedLabel;

            if (nodeMap.containsKey(limitKey)) {
                continue;
            }

            Core.LimitConfig config = coreNode.limitConfig();
            String inputExpr = (config != null && config.input() != null) ? config.input()
                : (coreNode.params() != null ? (String) coreNode.params().get("input") : null);
            LimitNode limitNode = LimitNode.builder()
                .nodeId(limitKey)
                .limitConfig(config)
                .inputExpression(inputExpr)
                .build();

            nodeMap.put(limitKey, limitNode);
            logger.info("📏 Created limit node: {} (count={}, from={})",
                limitKey,
                config != null ? config.count() : 0,
                config != null ? config.from() : "first");
        }
    }

    /**
     * Creates remove duplicates nodes from Core definitions.
     * RemoveDuplicates nodes deduplicate items by specified fields.
     */
    public void createRemoveDuplicatesNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"remove_duplicates".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String dedupKey = "core:" + normalizedLabel;

            if (nodeMap.containsKey(dedupKey)) {
                continue;
            }

            Core.RemoveDuplicatesConfig config = coreNode.removeDuplicatesConfig();
            // Input can come from config.input() or params.input (frontend writes to params)
            String inputExpr = config != null && config.input() != null && !config.input().isBlank()
                ? config.input()
                : (coreNode.params() != null ? (String) coreNode.params().get("input") : null);
            RemoveDuplicatesNode dedupNode = RemoveDuplicatesNode.builder()
                .nodeId(dedupKey)
                .removeDuplicatesConfig(config)
                .inputExpression(inputExpr)
                .build();

            nodeMap.put(dedupKey, dedupNode);
            logger.info("🔂 Created remove duplicates node: {} (fields={}, keep={})",
                dedupKey,
                config != null ? config.fields().size() : 0,
                config != null ? config.keep() : "first");
        }
    }

    /**
     * Creates summarize nodes from Core definitions.
     * Summarize nodes aggregate data with operations like sum, avg, count, min, max.
     */
    public void createSummarizeNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"summarize".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String summarizeKey = "core:" + normalizedLabel;

            if (nodeMap.containsKey(summarizeKey)) {
                continue;
            }

            Core.SummarizeConfig config = coreNode.summarizeConfig();
            // Input can come from config.input() or params.input (frontend writes to params)
            String inputExpr = config != null && config.input() != null && !config.input().isBlank()
                ? config.input()
                : (coreNode.params() != null ? (String) coreNode.params().get("input") : null);
            // Override config with params.input if needed
            if (inputExpr != null && config != null && (config.input() == null || config.input().isBlank())) {
                config = new Core.SummarizeConfig(config.aggregations(), config.groupBy(), inputExpr);
            }
            SummarizeNode summarizeNode = SummarizeNode.builder()
                .nodeId(summarizeKey)
                .summarizeConfig(config)
                .build();

            nodeMap.put(summarizeKey, summarizeNode);
            logger.info("📊 Created summarize node: {} (aggregations={}, groupBy={})",
                summarizeKey,
                config != null ? config.aggregations().size() : 0,
                config != null ? config.groupBy().size() : 0);
        }
    }

    /**
     * Creates date/time nodes from Core definitions.
     * DateTime nodes parse, format, convert, and manipulate date/time values.
     */
    public void createDateTimeNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"date_time".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String dateTimeKey = "core:" + normalizedLabel;

            if (nodeMap.containsKey(dateTimeKey)) {
                continue;
            }

            Core.DateTimeConfig config = coreNode.dateTimeConfig();
            DateTimeNode dateTimeNode = DateTimeNode.builder()
                .nodeId(dateTimeKey)
                .dateTimeConfig(config)
                .build();

            nodeMap.put(dateTimeKey, dateTimeNode);
            logger.info("📅 Created date/time node: {} (operation={})",
                dateTimeKey,
                config != null ? config.operation() : "format");
        }
    }

    /**
     * Creates crypto/JWT nodes from Core definitions.
     * CryptoJwt nodes handle hashing, encryption, JWT, and encoding operations.
     */
    public void createCryptoJwtNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) {
            return;
        }

        for (Core coreNode : plan.getCores()) {
            if (!"crypto_jwt".equals(coreNode.type())) {
                continue;
            }

            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String cryptoKey = "core:" + normalizedLabel;

            if (nodeMap.containsKey(cryptoKey)) {
                continue;
            }

            Core.CryptoJwtConfig config = coreNode.cryptoJwtConfig();
            CryptoJwtNode cryptoNode = CryptoJwtNode.builder()
                .nodeId(cryptoKey)
                .cryptoJwtConfig(config)
                .build();

            nodeMap.put(cryptoKey, cryptoNode);
            logger.info("🔐 Created crypto/JWT node: {} (operation={}, algorithm={})",
                cryptoKey,
                config != null ? config.operation() : "hash",
                config != null ? config.algorithm() : "SHA-256");
        }
    }

    /**
     * Creates XML nodes from Core definitions.
     */
    public void createXmlNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) return;
        for (Core coreNode : plan.getCores()) {
            if (!"xml".equals(coreNode.type())) continue;
            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String xmlKey = "core:" + normalizedLabel;
            if (nodeMap.containsKey(xmlKey)) continue;
            Core.XmlConfig config = coreNode.xmlConfig();
            XmlNode xmlNode = XmlNode.builder().nodeId(xmlKey).xmlConfig(config).build();
            nodeMap.put(xmlKey, xmlNode);
            logger.info("📄 Created XML node: {} (operation={})", xmlKey, config != null ? config.operation() : "xmlToJson");
        }
    }

    /**
     * Creates compression nodes from Core definitions.
     */
    public void createCompressionNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) return;
        for (Core coreNode : plan.getCores()) {
            if (!"compression".equals(coreNode.type())) continue;
            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String compKey = "core:" + normalizedLabel;
            if (nodeMap.containsKey(compKey)) continue;
            Core.CompressionConfig config = coreNode.compressionConfig();
            CompressionNode compNode = CompressionNode.builder().nodeId(compKey).compressionConfig(config).build();
            nodeMap.put(compKey, compNode);
            logger.info("📦 Created compression node: {} (operation={}, format={})", compKey,
                config != null ? config.operation() : "compress", config != null ? config.format() : "gzip");
        }
    }

    /**
     * Creates RSS nodes from Core definitions.
     */
    public void createRssNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) return;
        for (Core coreNode : plan.getCores()) {
            if (!"rss".equals(coreNode.type())) continue;
            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String rssKey = "core:" + normalizedLabel;
            if (nodeMap.containsKey(rssKey)) continue;
            Core.RssConfig config = coreNode.rssConfig();
            RssNode rssNode = RssNode.builder().nodeId(rssKey).rssConfig(config).build();
            nodeMap.put(rssKey, rssNode);
            logger.info("📡 Created RSS node: {} (url={}, maxItems={})", rssKey,
                config != null ? config.url() : "none", config != null ? config.maxItems() : 20);
        }
    }

    /**
     * Creates convert-to-file nodes from Core definitions.
     * ConvertToFile nodes export JSON data to CSV, XLSX, JSON, or TXT files.
     */
    public void createConvertToFileNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) return;
        for (Core coreNode : plan.getCores()) {
            if (!"convert_to_file".equals(coreNode.type())) continue;
            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String key = "core:" + normalizedLabel;
            if (nodeMap.containsKey(key)) continue;
            Core.ConvertToFileConfig config = coreNode.convertToFileConfig();
            ConvertToFileNode node = ConvertToFileNode.builder().nodeId(key).convertToFileConfig(config).build();
            nodeMap.put(key, node);
            logger.info("📄 Created convert-to-file node: {} (format={})", key,
                config != null ? config.format() : "csv");
        }
    }

    /**
     * Creates extract-from-file nodes from Core definitions.
     * ExtractFromFile nodes parse CSV, XLSX, or JSON files into JSON items.
     */
    public void createExtractFromFileNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) return;
        for (Core coreNode : plan.getCores()) {
            if (!"extract_from_file".equals(coreNode.type())) continue;
            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String key = "core:" + normalizedLabel;
            if (nodeMap.containsKey(key)) continue;
            Core.ExtractFromFileConfig config = coreNode.extractFromFileConfig();
            ExtractFromFileNode node = ExtractFromFileNode.builder().nodeId(key).extractFromFileConfig(config).build();
            nodeMap.put(key, node);
            logger.info("📥 Created extract-from-file node: {} (format={})", key,
                config != null ? config.format() : "csv");
        }
    }

    /**
     * Creates compare-datasets nodes from Core definitions.
     * CompareDatasets nodes compare two datasets and output matched, only-in-A, only-in-B.
     */
    public void createCompareDatasetsNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) return;
        for (Core coreNode : plan.getCores()) {
            if (!"compare_datasets".equals(coreNode.type())) continue;
            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String key = "core:" + normalizedLabel;
            if (nodeMap.containsKey(key)) continue;
            Core.CompareDatasetsConfig config = coreNode.compareDatasetsConfig();
            CompareDatasetsNode node = CompareDatasetsNode.builder().nodeId(key).compareDatasetsConfig(config).build();
            nodeMap.put(key, node);
            logger.info("🔀 Created compare-datasets node: {} (matchFields={})", key,
                config != null ? config.matchFields() : "[]");
        }
    }

    /**
     * Creates sub-workflow nodes from Core definitions.
     * SubWorkflow nodes execute another workflow as a function.
     */
    public void createSubWorkflowNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) return;
        for (Core coreNode : plan.getCores()) {
            if (!"sub_workflow".equals(coreNode.type())) continue;
            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String key = "core:" + normalizedLabel;
            if (nodeMap.containsKey(key)) continue;
            Core.SubWorkflowConfig config = coreNode.subWorkflowConfig();
            SubWorkflowNode node = SubWorkflowNode.builder().nodeId(key).subWorkflowConfig(config).build();
            nodeMap.put(key, node);
            logger.info("🔗 Created sub-workflow node: {} (workflowId={}, timeout={}s)", key,
                config != null ? config.workflowId() : "null",
                config != null ? config.timeoutSeconds() : 300);
        }
    }

    /**
     * Creates respond-to-webhook nodes from Core definitions.
     * RespondToWebhook nodes control the HTTP response returned to webhook callers.
     */
    public void createRespondToWebhookNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) return;
        for (Core coreNode : plan.getCores()) {
            if (!"respond_to_webhook".equals(coreNode.type())) continue;
            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String key = "core:" + normalizedLabel;
            if (nodeMap.containsKey(key)) continue;
            Core.RespondToWebhookConfig config = coreNode.respondToWebhookConfig();
            RespondToWebhookNode node = RespondToWebhookNode.builder().nodeId(key).respondToWebhookConfig(config).build();
            nodeMap.put(key, node);
            logger.info("📨 Created respond-to-webhook node: {} (statusCode={})", key,
                config != null ? config.statusCode() : 200);
        }
    }

    /**
     * Creates send-email nodes from Core definitions.
     * SendEmail nodes send emails via SMTP with user-provided credentials.
     */
    public void createSendEmailNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) return;
        for (Core coreNode : plan.getCores()) {
            if (!"send_email".equals(coreNode.type())) continue;
            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String key = "core:" + normalizedLabel;
            if (nodeMap.containsKey(key)) continue;
            Core.SendEmailConfig config = coreNode.sendEmailConfig();
            SendEmailNode node = SendEmailNode.builder().nodeId(key).sendEmailConfig(config).build();
            nodeMap.put(key, node);
            logger.info("📧 Created send-email node: {} (to={})", key,
                config != null ? config.toEmail() : "none");
        }
    }

    /**
     * Creates email-inbox nodes from Core definitions.
     * EmailInbox nodes read messages and act on a mailbox via IMAP with user-provided credentials.
     */
    public void createEmailInboxNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) return;
        for (Core coreNode : plan.getCores()) {
            if (!"email_inbox".equals(coreNode.type())) continue;
            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String key = "core:" + normalizedLabel;
            if (nodeMap.containsKey(key)) continue;
            Core.EmailInboxConfig config = coreNode.emailInboxConfig();
            EmailInboxNode node = EmailInboxNode.builder().nodeId(key).emailInboxConfig(config).build();
            nodeMap.put(key, node);
            logger.info("📥 Created email-inbox node: {} (folder={}, action={})", key,
                config != null ? config.folder() : "INBOX",
                config != null ? config.action() : "none");
        }
    }

    /**
     * Creates code nodes from Core definitions.
     * Code nodes execute user code in a sandboxed environment via Piston.
     */
    public void createCodeNodes(Map<String, ExecutionNode> nodeMap, WorkflowPlan plan) {
        if (plan.getCores() == null) return;
        for (Core coreNode : plan.getCores()) {
            if (!"code".equals(coreNode.type())) continue;
            String label = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String key = "core:" + normalizedLabel;
            if (nodeMap.containsKey(key)) continue;
            Core.CodeConfig config = coreNode.codeConfig();
            CodeNode node = CodeNode.builder().nodeId(key).codeConfig(config).build();
            nodeMap.put(key, node);
            logger.info("💻 Created code node: {} (language={}, codeLength={}, codePreview={})", key,
                config != null ? config.language() : "javascript",
                config != null && config.code() != null ? config.code().length() : 0,
                config != null && config.code() != null ? config.code().substring(0, Math.min(80, config.code().length())) : "null");
        }
    }

}
