package com.youtubeauto.orchestrator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    @Bean(name = "pipelineExecutor")
    public Executor pipelineExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(20);
        ex.setThreadNamePrefix("pipeline-");
        ex.initialize();
        return ex;
    }

    /**
     * P3b — dedicated pool for the Veo stage. A single Veo run is one long
     * blocking call (5–15 min wallclock per job; see architecture.md §8). Left
     * on the shared pipelineExecutor (max 4) it would hold a thread for minutes
     * and starve the quick stages (script / assets / assembly / upload). Its own
     * pool keeps the cheap stages responsive while Veo jobs grind in the
     * background. Queue is generous so concurrent jobs wait rather than reject.
     */
    @Bean(name = "veoExecutor")
    public Executor veoExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(50);
        ex.setThreadNamePrefix("veo-");
        ex.initialize();
        return ex;
    }
}
