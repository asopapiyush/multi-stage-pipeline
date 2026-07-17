package com.pipeline.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pipeline.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;
import org.springframework.util.StreamUtils;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

@Repository
public class JobRepository {
    private static final Logger log = LoggerFactory.getLogger(JobRepository.class);

    private final DataSource dataSource;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JobRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void initializeSchema() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            String schema = StreamUtils.copyToString(
                new ClassPathResource("schema.sql").getInputStream(),
                StandardCharsets.UTF_8
            );

            for (String sql : schema.split(";")) {
                if (!sql.trim().isEmpty()) {
                    stmt.execute(sql);
                }
            }
            log.info("Schema initialized successfully");
        } catch (Exception e) {
            log.error("Failed to initialize schema", e);
        }
    }

    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void createJob(String jobId, JobStatus status) {
        String sql = "INSERT INTO jobs (id, state, created_at, updated_at) VALUES (?, ?, ?, ?) " +
            "ON CONFLICT (id) DO UPDATE SET state = EXCLUDED.state, created_at = EXCLUDED.created_at, updated_at = EXCLUDED.updated_at";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, jobId);
            ps.setString(2, status.state().name());
            ps.setLong(3, status.createdAt());
            ps.setLong(4, System.currentTimeMillis());

            ps.executeUpdate();
            log.debug("Created job: {}", jobId);

            // Initialize empty aggregate
            initializeAggregate(jobId, conn);

        } catch (SQLException e) {
            log.error("Failed to create job: {}", jobId, e);
        }
    }

    private void initializeAggregate(String jobId, Connection conn) {
        String sql = "INSERT INTO job_aggregates (job_id, documents_processed, average_readability, last_updated) VALUES (?, 0, 0.0, ?) " +
            "ON CONFLICT (job_id) DO NOTHING";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jobId);
            ps.setLong(2, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to initialize aggregate for job: {}", jobId, e);
        }
    }

    public void updateJobItem(String jobId, ItemStatus item) {
        String sql = "INSERT INTO job_items (job_id, url, item_index, stage, state, error, started_at, ended_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (job_id, item_index) DO UPDATE SET " +
            "url = EXCLUDED.url, stage = EXCLUDED.stage, state = EXCLUDED.state, error = EXCLUDED.error, " +
            "started_at = EXCLUDED.started_at, ended_at = EXCLUDED.ended_at";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, jobId);
            ps.setString(2, item.url());
            ps.setInt(3, item.index());
            ps.setString(4, item.stage() != null ? item.stage().name() : null);
            ps.setString(5, item.state() != null ? item.state().name() : null);
            ps.setString(6, item.error());
            ps.setLong(7, item.startTime());
            ps.setLong(8, item.endTime());

            ps.executeUpdate();
            log.debug("Updated item for job {}: index={}", jobId, item.index());

        } catch (SQLException e) {
            log.error("Failed to update item for job: {}", jobId, e);
        }
    }

    public void saveResult(String jobId, AnalysisResult result) {
        String sql = "INSERT INTO job_results (job_id, url, links, word_freq, readability_score) VALUES (?, ?, ?, ?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, jobId);
            ps.setString(2, result.url());
            ps.setString(3, objectMapper.writeValueAsString(result.links()));
            ps.setString(4, objectMapper.writeValueAsString(result.wordFrequencies()));
            ps.setDouble(5, result.readabilityScore());

            ps.executeUpdate();
            log.debug("Saved result for job {}: url={}", jobId, result.url());

        } catch (Exception e) {
            log.error("Failed to save result for job: {}", jobId, e);
        }
    }

    public void updateAggregate(String jobId, JobAggregate agg) {
        String sql = "UPDATE job_aggregates SET documents_processed = ?, documents_errored = ?, average_readability = ?, top_words = ?, last_updated = ? WHERE job_id = ?";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, agg.documentsProcessed());
            ps.setInt(2, agg.documentsErrored());
            ps.setDouble(3, agg.averageReadability());
            ps.setString(4, objectMapper.writeValueAsString(agg.topWords()));
            ps.setLong(5, System.currentTimeMillis());
            ps.setString(6, jobId);

            ps.executeUpdate();
            log.debug("Updated aggregate for job {}: count={}", jobId, agg.documentsProcessed());

        } catch (Exception e) {
            log.error("Failed to update aggregate for job: {}", jobId, e);
        }
    }

    public Optional<JobStatus> getJob(String jobId) {
        String sql = "SELECT id, state, created_at, updated_at FROM jobs WHERE id = ?";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, jobId);
            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                JobStatus status = new JobStatus();
                status.setJobId(rs.getString("id"));
                status.setState(JobState.valueOf(rs.getString("state")));
                status.setCreatedAt(rs.getLong("created_at"));
                status.setUpdatedAt(rs.getLong("updated_at"));

                // Fetch items
                status.setItems(getJobItems(jobId, conn));

                // Fetch aggregates
                status.setAggregates(getJobAggregate(jobId, conn));

                return Optional.of(status);
            }

        } catch (Exception e) {
            log.error("Failed to get job: {}", jobId, e);
        }

        return Optional.empty();
    }

    private List<ItemStatus> getJobItems(String jobId, Connection conn) {
        List<ItemStatus> items = new ArrayList<>();
        String sql = "SELECT url, item_index, stage, state, error, started_at, ended_at FROM job_items WHERE job_id = ? ORDER BY item_index";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jobId);
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                ItemStatus item = new ItemStatus();
                item.setUrl(rs.getString("url"));
                item.setIndex(rs.getInt("item_index"));
                item.setStage(rs.getString("stage") != null ? ProcessingStage.valueOf(rs.getString("stage")) : null);
                item.setState(rs.getString("state") != null ? ProcessingState.valueOf(rs.getString("state")) : null);
                item.setError(rs.getString("error"));
                item.setStartTime(rs.getLong("started_at"));
                item.setEndTime(rs.getLong("ended_at"));

                items.add(item);
            }
        } catch (SQLException e) {
            log.error("Failed to get items for job: {}", jobId, e);
        }

        return items;
    }

    private JobAggregate getJobAggregate(String jobId, Connection conn) {
        String sql = "SELECT documents_processed, documents_errored, average_readability, top_words, last_updated FROM job_aggregates WHERE job_id = ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jobId);
            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                JobAggregate agg = new JobAggregate();
                agg.setDocumentsProcessed(rs.getInt("documents_processed"));
                agg.setDocumentsErrored(rs.getInt("documents_errored"));
                agg.setAverageReadability(rs.getDouble("average_readability"));
                agg.setLastUpdated(rs.getLong("last_updated"));

                String topWordsJson = rs.getString("top_words");
                if (topWordsJson != null && !topWordsJson.isEmpty()) {
                    Map<String, Long> topWords = objectMapper.readValue(topWordsJson,
                        objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Long.class));
                    agg.setTopWords(topWords);
                }

                return agg;
            }
        } catch (Exception e) {
            log.error("Failed to get aggregate for job: {}", jobId, e);
        }

        return new JobAggregate();
    }

    public List<JobStatus> listJobs() {
        List<JobStatus> jobs = new ArrayList<>();
        String sql = "SELECT id, state, created_at, updated_at FROM jobs ORDER BY created_at DESC";

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                JobStatus status = new JobStatus();
                status.setJobId(rs.getString("id"));
                status.setState(JobState.valueOf(rs.getString("state")));
                status.setCreatedAt(rs.getLong("created_at"));
                status.setUpdatedAt(rs.getLong("updated_at"));

                status.setItems(getJobItems(status.jobId(), conn));
                status.setAggregates(getJobAggregate(status.jobId(), conn));

                jobs.add(status);
            }

        } catch (SQLException e) {
            log.error("Failed to list jobs", e);
        }

        return jobs;
    }
}
