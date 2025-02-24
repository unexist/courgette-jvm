package courgette.runtime;

import courgette.api.CourgetteOptions;
import courgette.api.CourgettePlugin;
import courgette.api.CourgetteRunLevel;
import courgette.api.CourgetteTestOutput;
import courgette.api.CucumberOptions;
import courgette.api.HtmlReport;
import courgette.integration.reportportal.ReportPortalProperties;
import courgette.runtime.event.CourgetteEvent;
import courgette.runtime.utils.FileUtils;
import courgette.runtime.utils.SystemPropertyUtils;

import java.io.File;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.stream.Collectors;

public class CourgetteRunOptions implements CourgetteOptions {
    private CourgetteOptions courgetteOptions;

    public CourgetteRunOptions(Class clazz) {
        validate(clazz);
        validatePlugins();
        validateSlackOptions();
    }

    @Override
    public int threads() {
        return SystemPropertyUtils.getIntProperty(CourgetteSystemProperty.THREADS, courgetteOptions.threads());
    }

    @Override
    public CourgetteRunLevel runLevel() {
        return SystemPropertyUtils.getEnumProperty(CourgetteSystemProperty.RUN_LEVEL, CourgetteRunLevel.class, courgetteOptions.runLevel());
    }

    @Override
    public boolean rerunFailedScenarios() {
        return SystemPropertyUtils.getBoolProperty(CourgetteSystemProperty.RERUN_FAILED_SCENARIOS, courgetteOptions.rerunFailedScenarios());
    }

    @Override
    public String[] excludeFeatureFromRerun() {
        return SystemPropertyUtils.getStringArrayProperty(CourgetteSystemProperty.EXCLUDE_FEATURE_FROM_RERUN, courgetteOptions.excludeFeatureFromRerun());
    }

    @Override
    public String[] excludeTagFromRerun() {
        return SystemPropertyUtils.getStringArrayProperty(CourgetteSystemProperty.EXCLUDE_TAG_FROM_RERUN, courgetteOptions.excludeTagFromRerun());
    }

    @Override
    public int rerunAttempts() {
        return SystemPropertyUtils.getIntProperty(CourgetteSystemProperty.RERUN_ATTEMPTS, courgetteOptions.rerunAttempts());
    }

    @Override
    public CourgetteTestOutput testOutput() {
        return courgetteOptions.testOutput();
    }

    @Override
    public String reportTitle() {
        return SystemPropertyUtils.getNonEmptyStringProperty(CourgetteSystemProperty.REPORT_TITLE, courgetteOptions.reportTitle(), "Courgette-JVM Report");
    }

    @Override
    public String reportTargetDir() {
        return SystemPropertyUtils.getNonEmptyStringProperty(CourgetteSystemProperty.REPORT_TARGET_DIR, courgetteOptions.reportTargetDir(), "target");
    }

    @Override
    public CucumberOptions cucumberOptions() {
        return courgetteOptions.cucumberOptions();
    }

    @Override
    public String[] plugin() {
        return SystemPropertyUtils.getStringArrayProperty(CourgetteSystemProperty.PLUGIN, courgetteOptions.plugin());
    }

    @Override
    public String environmentInfo() {
        return SystemPropertyUtils.getNonEmptyStringProperty(CourgetteSystemProperty.ENVIRONMENT_INFO, courgetteOptions.environmentInfo(), "");
    }

    @Override
    public HtmlReport[] disableHtmlReport() {
        return courgetteOptions.disableHtmlReport();
    }

    @Override
    public boolean persistParallelCucumberJsonReports() {
        return SystemPropertyUtils.getBoolProperty(CourgetteSystemProperty.PERSIST_PARALLEL_CUCUMBER_JSON_REPORTS, courgetteOptions.persistParallelCucumberJsonReports());
    }

    @Override
    public String[] classPath() {
        return SystemPropertyUtils.getStringArrayProperty(CourgetteSystemProperty.CLASS_PATH, courgetteOptions.classPath());
    }

    @Override
    public String slackWebhookUrl() {
        return SystemPropertyUtils.getNonEmptyStringProperty(CourgetteSystemProperty.SLACK_WEBHOOK_URL, courgetteOptions.slackWebhookUrl(), "");
    }

    @Override
    public String[] slackChannel() {
        return SystemPropertyUtils.getStringArrayProperty(CourgetteSystemProperty.SLACK_CHANNEL, courgetteOptions.slackChannel());
    }

    @Override
    public String slackTestId() {
        return SystemPropertyUtils.getNonEmptyStringProperty(CourgetteSystemProperty.SLACK_TEST_ID, courgetteOptions.slackTestId(), "");
    }

    @Override
    public CourgetteEvent[] slackEventSubscription() {
        return courgetteOptions.slackEventSubscription();
    }

    @Override
    public String[] mobileDevice() {
        return SystemPropertyUtils.getStringArrayProperty(CourgetteSystemProperty.MOBILE_DEVICE, courgetteOptions.mobileDevice());
    }

    @Override
    public Class<? extends Annotation> annotationType() {
        return null;
    }

    private void validate(Class clazz) {
        courgetteOptions = (CourgetteOptions) Arrays.stream(clazz.getDeclaredAnnotations())
                .filter(annotation -> annotation.annotationType().equals(CourgetteOptions.class))
                .findFirst()
                .orElseThrow(() -> new CourgetteException("Runner class is not annotated with @CourgetteOptions"));
    }

    private void validatePlugins() {
        if (plugin().length > 0) {
            validateReportPortalPlugin();
            validateMobileDeviceAllocatorPlugin();
        }
    }

    private void validateReportPortalPlugin() {
        final String reportPortalPropertiesFilename = "reportportal.properties";

        if (Arrays.stream(plugin()).anyMatch(plugin -> plugin.equalsIgnoreCase(CourgettePlugin.REPORT_PORTAL))) {
            File reportPortalPropertiesFile = FileUtils.getClassPathFile(reportPortalPropertiesFilename);

            if (reportPortalPropertiesFile == null) {
                throw new CourgetteException("The " + reportPortalPropertiesFilename + " file must be in your classpath to use the Courgette reportportal plugin");
            }
            ReportPortalProperties.getInstance().validate();
        }
    }

    private void validateMobileDeviceAllocatorPlugin() {
        if (Arrays.stream(plugin()).anyMatch(plugin -> plugin.equalsIgnoreCase(CourgettePlugin.MOBILE_DEVICE_ALLOCATOR))) {
            String[] mobileDevice = mobileDevice();
            if (mobileDevice.length == 0 ||
                    Arrays.stream(mobileDevice)
                            .map(device -> device.replace(":", ""))
                            .map(String::trim)
                            .collect(Collectors.toSet())
                            .stream()
                            .allMatch(device -> device.equals(""))) {
                throw new CourgetteException("Mobile device is required when using the Courgette Mobile Device Allocator plugin");
            }
        }
    }

    private void validateSlackOptions() {
        final CourgetteSlackOptions slackOptions = new CourgetteSlackOptions(
                slackWebhookUrl(), Arrays.asList(slackChannel()), slackTestId(), Arrays.asList(slackEventSubscription()));

        if (slackOptions.shouldValidate() && !slackOptions.isValid()) {
            throw new CourgetteException("You must provide a Slack webhook URL and valid Slack channels");
        }
    }
}