package com.example;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.integration.launch.JobLaunchingGateway;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.mapping.PassThroughLineMapper;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.core.MessageSource;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.integration.dsl.core.Pollers;
import org.springframework.integration.file.FileReadingMessageSource;
import org.springframework.integration.file.FileReadingMessageSource.WatchEventType;
import org.springframework.integration.file.filters.SimplePatternFileListFilter;
import org.springframework.integration.handler.LoggingHandler;
import org.springframework.integration.jdbc.JdbcPollingChannelAdapter;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.io.File;


// Java DSL described here
// https://github.com/spring-projects/spring-integration-java-dsl/wiki/spring-integration-java-dsl-reference

// BatchAutoConfiguration -- from Spring Boot
// SimpleBatchConfiguration - from Spring Batch Core

// Spring Batch integration using job launching gateway
// https://stackoverflow.com/questions/27770377/spring-batch-integration-job-launching-gateway

// decoupled event-driven execution of the JobLauncher

// Related examples
// https://github.com/pakmans/spring-batch-integration-example


@Slf4j
@Configuration
@EnableIntegration
@EnableBatchProcessing
public class MyConfiguration {

    @Autowired
    private JobBuilderFactory jobBuilderFactory;

    @Autowired
    private StepBuilderFactory stepBuilderFactory;

    @Autowired
    private JobLaunchingGateway jobLaunchingGateway;

    @Autowired
    private JdbcPollingChannelAdapter jdbcPollingChannelAdapter;

    @Bean
    public MessageSource<File> fileReadingMessageSource() {
        FileReadingMessageSource source = new FileReadingMessageSource();
        source.setDirectory(new File("dropfolder"));
        source.setFilter(new SimplePatternFileListFilter("*.txt"));
        source.setUseWatchService(true);
        source.setWatchEvents(WatchEventType.CREATE);
        return source;
    }

    @Bean
    public JdbcPollingChannelAdapter jdbcPollingChannelAdapter(DataSource datasource) {
        JdbcPollingChannelAdapter jdbcPollingChannelAdapter = new JdbcPollingChannelAdapter
                (datasource, "select * from external_batch_job_execution where status = 'FINISHED'");
        jdbcPollingChannelAdapter.setUpdateSql("update external_batch_job_execution set status = 'PROCESSED' where status = 'FINISHED'");
        jdbcPollingChannelAdapter.setRowMapper((RowMapper<ExternalBatchJob>) (rs, rowNum) ->
                new ExternalBatchJob(rs.getDate("end_date"), rs.getString("status"))
        );
        return jdbcPollingChannelAdapter;
    }

    //@Bean
    public IntegrationFlow myFileTriggeredFlow() {
        return IntegrationFlows.from(fileReadingMessageSource(),
                c -> c.poller(Pollers.fixedRate(5000, 2000)))
                .transform(fileMessageToJobLaunchRequest())
                .handle(jobLaunchingGateway)
                .handle(logger())
                .get();
    }

    @Bean
    public IntegrationFlow myDatabaseTriggeredFlow() {
        return IntegrationFlows.from(jdbcPollingChannelAdapter,
                c -> c.poller(Pollers.fixedRate(5000, 2000)))
                .transform(listMessageToJobLaunchRequest())
                .handle(jobLaunchingGateway)
                .handle(logger())
                .get();
    }

    @Bean
    ListMessageToJobLaunchRequest listMessageToJobLaunchRequest() {
        ListMessageToJobLaunchRequest transformer = new ListMessageToJobLaunchRequest();
        transformer.setJob(dummyJob());
        return transformer;
    }


    @Bean
    FileMessageToJobLaunchRequest fileMessageToJobLaunchRequest() {
        FileMessageToJobLaunchRequest transformer = new FileMessageToJobLaunchRequest();
        transformer.setJob(exampleJob());
        transformer.setFileParameterName("file_path");
        return transformer;
    }

    @Bean
    LoggingHandler logger() {
        return new LoggingHandler("INFO");
    }

    @Bean
    JobLaunchingGateway jobLaunchingGateway(JobLauncher jobLauncher) {
        return new JobLaunchingGateway(jobLauncher);
    }

    // ----------------------------------------------------------------------------------------- //

    @Bean
    Job dummyJob() {
        return jobBuilderFactory.get("dummyJob")
                .start(dummyStep())
                .next(extractStep())
                .build();
    }

    @Bean
    Step dummyStep() {
        return stepBuilderFactory.get("dummyStep").tasklet((contribution, chunkContext) -> {
            log.info("Dummy step executed");
            return RepeatStatus.FINISHED;
        }).build();
    }

    @Bean
    Step extractStep() {
        return stepBuilderFactory.get("extractStep").tasklet(myTasklet()).build();
    }

    @Bean
    Job exampleJob() {
        return jobBuilderFactory.get("exampleJob")
                .start(exampleStep())
                .build();
    }

    @Bean
    Step exampleStep() {
        return stepBuilderFactory.get("exampleStep")
                .<String, String>chunk(5)
                .reader(itemReader(null))
                .writer(i -> i.stream().forEach(j -> System.out.println(j)))
                .build();
    }

    @Bean
    Tasklet myTasklet() {
        return (contribution, chunkContext) -> {
            Object endDate = chunkContext.getStepContext().getJobParameters().get("end_date");
            log.info("Tasklet received date: {}", endDate);
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    @StepScope
    FlatFileItemReader<String> itemReader(@Value("#{jobParameters[file_path]}") String filePath) {
        FlatFileItemReader<String> reader = new FlatFileItemReader<>();
        FileSystemResource fileResource = new FileSystemResource(filePath);
        reader.setResource(fileResource);
        reader.setLineMapper(new PassThroughLineMapper());
        return reader;
    }

}
