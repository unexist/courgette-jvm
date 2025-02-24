package courgette.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import courgette.api.CourgetteRunLevel;
import courgette.integration.extentreports.ExtentReportsBuilder;
import courgette.integration.extentreports.ExtentReportsProperties;
import courgette.integration.reportportal.ReportPortalPublisher;
import courgette.integration.slack.SlackPublisher;
import courgette.runtime.event.CourgetteEvent;
import courgette.runtime.event.CourgetteEventHolder;
import courgette.runtime.report.JsonReportParser;
import courgette.runtime.report.model.Feature;
import courgette.runtime.utils.FileUtils;
import io.cucumber.messages.types.Envelope;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static courgette.runtime.CourgetteException.printExceptionStackTrace;
import static courgette.runtime.utils.FileUtils.readFile;
import static courgette.runtime.utils.FileUtils.writeFile;

public class CourgetteRunner {
    private final List<Callable<Boolean>> runners = new ArrayList<>();
    private final CopyOnWriteArrayList<String> reruns = new CopyOnWriteArrayList<>();
    private final Map<String, CopyOnWriteArrayList<String>> reports = new HashMap<>();
    private final List<CourgetteRunnerInfo> runnerInfoList;
    private final CourgetteProperties courgetteProperties;
    private final CourgetteRuntimeOptions defaultRuntimeOptions;
    private final CourgetteTestStatistics testStatistics;
    private final List<CourgetteRunResult> runResults = new ArrayList<>();
    private final CourgetteRuntimePublisher runtimePublisher;
    private final CourgettePluginService courgettePluginService;
    private final boolean canRunFeatures;
    private final AtomicReference<RunStatus> runStatus = new AtomicReference<>(RunStatus.OK);
    private List<Feature> reportFeatures = new ArrayList<>();
    private final Map<io.cucumber.core.gherkin.Feature, List<List<Envelope>>> reportMessages = new HashMap<>();
    private String cucumberReportUrl = "#";

    public CourgetteRunner(List<CourgetteRunnerInfo> runnerInfoList, CourgetteProperties courgetteProperties) {
        this.runnerInfoList = runnerInfoList;
        this.canRunFeatures = runnerInfoList.size() > 0;
        this.courgetteProperties = courgetteProperties;
        this.testStatistics = CourgetteTestStatistics.current();
        this.defaultRuntimeOptions = new CourgetteRuntimeOptions(courgetteProperties);
        this.runtimePublisher = createRuntimePublisher(courgetteProperties, extractRunnerInfoFeatures());
        this.courgettePluginService = createCourgettePluginService();
    }

    public RunStatus run() {
        final ExecutorService executor = Executors.newFixedThreadPool(optimizedThreadCount());

        final Queue<CourgetteRunnerInfo> runnerQueue = new ArrayDeque<>(runnerInfoList);

        while (!runnerQueue.isEmpty()) {
            final CourgetteRunnerInfo runnerInfo = runnerQueue.poll();

            final Map<String, List<String>> cucumberArgs = runnerInfo.getRuntimeOptions();

            final io.cucumber.core.gherkin.Feature feature = runnerInfo.getFeature();
            final Integer lineId = runnerInfo.getLineId();
            final String featureUri = cucumberArgs.get(null).get(0);

            this.runners.add(() -> {
                try {
                    if (runFeature(cucumberArgs)) {
                        addResultAndPublish(runnerInfo, new CourgetteRunResult(feature, lineId, featureUri, CourgetteRunResult.Status.PASSED));
                        return true;
                    }

                    String rerunFile = runnerInfo.getRerunFile();

                    String rerun = readFile(rerunFile, false);

                    if (runnerInfo.allowRerun() && rerun != null) {

                        if (courgetteProperties.isFeatureRunLevel()) {

                            CourgetteRunResult rerunResult = new CourgetteRunResult(feature, lineId, featureUri, CourgetteRunResult.Status.RERUN);
                            runResults.add(rerunResult);

                            if (rerunFeature(cucumberArgs, rerunResult)) {
                                addResultAndPublish(runnerInfo, new CourgetteRunResult(feature, lineId, featureUri, CourgetteRunResult.Status.PASSED_AFTER_RERUN));
                                return true;
                            } else {
                                addResultAndPublish(runnerInfo, new CourgetteRunResult(feature, lineId, featureUri, CourgetteRunResult.Status.FAILED_AFTER_RERUN));
                            }
                        } else {
                            final Map<String, List<String>> rerunCucumberArgs = runnerInfo.getRerunRuntimeOptions(rerun);

                            final String rerunFeatureUri = rerunCucumberArgs.get(null).get(0);

                            CourgetteRunResult rerunResult = new CourgetteRunResult(feature, lineId, rerunFeatureUri, CourgetteRunResult.Status.RERUN);
                            runResults.add(rerunResult);

                            if (rerunFeature(rerunCucumberArgs, rerunResult)) {
                                addResultAndPublish(runnerInfo, new CourgetteRunResult(feature, lineId, rerunFeatureUri, CourgetteRunResult.Status.PASSED_AFTER_RERUN));
                                return true;
                            }
                            addResultAndPublish(runnerInfo, new CourgetteRunResult(feature, lineId, rerunFeatureUri, CourgetteRunResult.Status.FAILED_AFTER_RERUN));
                        }
                    } else {
                        addResultAndPublish(runnerInfo, new CourgetteRunResult(feature, lineId, featureUri, CourgetteRunResult.Status.FAILED));
                    }

                    if (rerun != null) {
                        reruns.add(rerun);
                    }
                } finally {
                    runnerInfo.getReportFiles().forEach(reportFile -> {
                        if (shouldProcessReport(reportFile)) {
                            boolean isJson = reportFile.endsWith(".json");

                            String report = isJson
                                    ? prettyJson(readFile(reportFile, true))
                                    : readFile(reportFile, true);

                            boolean isNdJson = reportFile.endsWith(".ndjson");

                            if (isNdJson && shouldProcessCucumberMessages()) {
                                reportMessages.computeIfAbsent(feature, r -> new ArrayList<>())
                                        .addAll(Collections.singleton(CourgetteNdJsonCreator.createMessages(report)));
                            } else {
                                reports.computeIfAbsent(reportFile, r -> new CopyOnWriteArrayList<>()).add(report);
                            }
                        }
                    });
                }
                return false;
            });
        }

        try {
            runtimePublisher.publish(createEventHolder(CourgetteEvent.TEST_RUN_STARTED));
            executor.invokeAll(runners);
        } catch (InterruptedException e) {
            printExceptionStackTrace(e);
            runStatus.set(RunStatus.ERROR);
        } finally {
            testStatistics.calculate(runResults, courgetteProperties);
            runtimePublisher.publish(createEventHolder(CourgetteEvent.TEST_RUN_FINISHED));
            runtimePublisher.publish(createTestRunSummaryEventHolder());
            executor.shutdownNow();
        }

        boolean reportErrors = !reportMessages.isEmpty() && reportMessages.values().stream().anyMatch(List::isEmpty);
        if (reportErrors) {
            runStatus.set(RunStatus.REPORT_PROCESSING_ERROR);
        }

        return runStatus.get();
    }

    public void createCucumberReport() {
        final List<String> reportFiles = defaultRuntimeOptions.getReportFiles();

        final CourgetteReporter courgetteReporter = new CourgetteReporter(reports, reportMessages, defaultRuntimeOptions, courgetteProperties);

        reportFiles.forEach(reportFile -> {
            boolean mergeTestCaseName = courgetteProperties.isReportPortalPluginEnabled() && reportFile.equalsIgnoreCase(defaultRuntimeOptions.getCourgetteReportXmlForReportPortal());
            courgetteReporter.createCucumberReport(reportFile, mergeTestCaseName);
        });

        final Optional<String> publishedReport = courgetteReporter.publishCucumberReport();
        publishedReport.ifPresent(reportUrl -> cucumberReportUrl = reportUrl);
    }

    public void createRerunFile() {
        reruns.sort(String::compareTo);
        final List<String> rerun = new ArrayList<>(reruns);
        rerun.removeIf(r -> r.length() == 0);

        final String rerunFile = defaultRuntimeOptions.getCucumberRerunFile();

        if (rerunFile != null) {
            writeFile(rerunFile, String.join("\n", rerun));
        }
    }

    public void createCourgetteReport() {
        if (courgetteProperties.isCourgetteHtmlReportEnabled()) {
            try {
                final CourgetteHtmlReporter courgetteReport = new CourgetteHtmlReporter(courgetteProperties, runResults, getReportFeatures(), cucumberReportUrl);
                courgetteReport.create(testStatistics);
            } catch (Exception e) {
                printExceptionStackTrace(e);
            }
        }
    }

    public void createCourgettePluginReports() {
        if (courgetteProperties.isExtentReportsPluginEnabled()) {
            try {
                final ExtentReportsProperties extentReportsProperties = new ExtentReportsProperties(courgetteProperties);
                final ExtentReportsBuilder extentReportsBuilder = ExtentReportsBuilder.create(extentReportsProperties, getReportFeatures());
                extentReportsBuilder.buildReport();
            } catch (Exception e) {
                printExceptionStackTrace(e);
            }
        }
    }

    public List<CourgetteRunResult> getFailures() {
        return runResults.stream()
                .filter(t -> t.getStatus().equals(CourgetteRunResult.Status.FAILED) || t.getStatus().equals(CourgetteRunResult.Status.FAILED_AFTER_RERUN))
                .collect(Collectors.toList());
    }

    public boolean canRunFeatures() {
        return canRunFeatures;
    }

    public void cleanupCourgetteHtmlReportFiles() {
        FileUtils.deleteDirectorySilently(defaultRuntimeOptions.getCourgetteReportDataDirectory());
    }

    public void printCourgetteTestStatistics() {
        testStatistics.printToConsole(courgetteProperties);
    }

    public void printCourgetteTestFailures() {
        CourgetteTestFailure.printTestFailures(getFailures(), courgetteProperties.isFeatureRunLevel());
    }

    private boolean runFeature(Map<String, List<String>> args) {
        try {
            return 0 == new CourgetteFeatureRunner(args, courgetteProperties, courgettePluginService).run();
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            return false;
        }
    }

    private boolean rerunFeature(Map<String, List<String>> args, CourgetteRunResult rerunResult) {
        int rerunAttempts = courgetteProperties.getCourgetteOptions().rerunAttempts();

        rerunAttempts = Math.max(rerunAttempts, 1);

        while (rerunAttempts-- > 0) {
            runtimePublisher.publish(createEventHolder(CourgetteEvent.TEST_RERUN, null, rerunResult));
            args.put("retry", new ArrayList<>());
            if (runFeature(args)) {
                return true;
            }
        }
        return false;
    }

    private String prettyJson(String json) {
        final ObjectMapper mapper = new ObjectMapper();

        try {
            final Object jsonObject = mapper.readValue(json, Object.class);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject);
        } catch (IOException e) {
            return json;
        }
    }

    private List<Feature> getReportFeatures() {
        if (reportFeatures.isEmpty()) {
            final File reportJson = new File(defaultRuntimeOptions.getCourgetteReportJson());

            final CourgetteRunLevel runLevel = courgetteProperties.getCourgetteOptions().runLevel();

            if (reportJson.exists()) {
                reportFeatures = JsonReportParser.create(reportJson, runLevel).getReportFeatures();
            }
        }
        return reportFeatures;
    }

    private int optimizedThreadCount() {
        return requiredThreadCount() > runnerInfoList.size()
                ? runnerInfoList.size()
                : Math.max(requiredThreadCount(), 1);
    }

    private int requiredThreadCount() {
        if (courgetteProperties.isMobileDeviceAllocationPluginEnabled()) {
            int mobileDeviceThreads = courgetteProperties.getMaxThreadsFromMobileDevices();
            return mobileDeviceThreads > courgetteProperties.getMaxThreads()
                    ? courgetteProperties.getMaxThreads() : mobileDeviceThreads;
        } else {
            return courgetteProperties.getMaxThreads();
        }
    }

    private List<io.cucumber.core.gherkin.Feature> extractRunnerInfoFeatures() {
        return runnerInfoList.stream().map(CourgetteRunnerInfo::getFeature).collect(Collectors.toList());
    }

    private CourgetteRuntimePublisher createRuntimePublisher(CourgetteProperties courgetteProperties, List<io.cucumber.core.gherkin.Feature> features) {
        final Set<CourgettePublisher> publishers = new HashSet<>();
        publishers.add(new SlackPublisher(courgetteProperties));
        publishers.add(new ReportPortalPublisher(courgetteProperties, features));
        return new CourgetteRuntimePublisher(publishers);
    }

    private CourgettePluginService createCourgettePluginService() {
        final CourgetteMobileDeviceAllocatorService mobileDeviceAllocatorService =
                new CourgetteMobileDeviceAllocatorService(courgetteProperties.getCourgetteOptions().mobileDevice());

        return new CourgettePluginService(mobileDeviceAllocatorService);
    }

    private synchronized void addResultAndPublish(CourgetteRunnerInfo courgetteRunnerInfo, CourgetteRunResult courgetteRunResult) {
        runResults.add(courgetteRunResult);

        switch (courgetteRunResult.getStatus()) {
            case PASSED:
                runtimePublisher.publish(createEventHolder(CourgetteEvent.TEST_PASSED, courgetteRunnerInfo, courgetteRunResult));
                break;
            case PASSED_AFTER_RERUN:
                runtimePublisher.publish(createEventHolder(CourgetteEvent.TEST_PASSED_AFTER_RERUN, courgetteRunnerInfo, courgetteRunResult));
                break;
            case FAILED:
            case FAILED_AFTER_RERUN:
                runtimePublisher.publish(createEventHolder(CourgetteEvent.TEST_FAILED, courgetteRunnerInfo, courgetteRunResult));
                break;
        }
    }

    private CourgetteEventHolder createEventHolder(CourgetteEvent courgetteEvent) {
        return new CourgetteEventHolder(courgetteEvent, courgetteProperties);
    }

    private CourgetteEventHolder createEventHolder(CourgetteEvent courgetteEvent, CourgetteRunnerInfo courgetteRunnerInfo, CourgetteRunResult courgetteRunResult) {
        return new CourgetteEventHolder(courgetteEvent, courgetteProperties, courgetteRunnerInfo, courgetteRunResult);
    }

    private CourgetteEventHolder createTestRunSummaryEventHolder() {
        return new CourgetteEventHolder(CourgetteEvent.TEST_RUN_SUMMARY, courgetteProperties, testStatistics);
    }

    private boolean shouldProcessCucumberMessages() {
        return courgetteProperties.isCucumberHtmlReportEnabled();
    }

    private boolean shouldProcessReport(String reportFile) {
        return !reportFile.contains("/session-reports/") && reportFile.contains(courgetteProperties.getSessionId());
    }
}