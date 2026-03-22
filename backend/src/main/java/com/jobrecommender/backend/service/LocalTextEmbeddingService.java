package com.jobrecommender.backend.service;

import ai.djl.Application;
import ai.djl.Device;
import ai.djl.MalformedModelException;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import ai.djl.translate.TranslateException;
import com.jobrecommender.backend.config.SkillEmbeddingProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
public class LocalTextEmbeddingService {

    private final SkillEmbeddingProperties properties;

    private final ReentrantLock initLock = new ReentrantLock();

    private volatile ZooModel<String, float[]> model;
    private volatile Predictor<String, float[]> predictor;

    public List<Float> embed(String text) {
        if (!properties.isEnabled()) {
            return Collections.emptyList();
        }
        if (!StringUtils.hasText(text)) {
            return Collections.emptyList();
        }

        Predictor<String, float[]> currentPredictor = ensurePredictor();
        try {
            float[] raw = currentPredictor.predict(text);
            if (raw == null || raw.length == 0) {
                return Collections.emptyList();
            }

            float[] vector = properties.isNormalize() ? l2Normalize(raw) : raw;
            if (!isUsableVector(vector)) {
                return Collections.emptyList();
            }

            List<Float> out = new ArrayList<>(vector.length);
            for (float v : vector) {
                out.add(v);
            }
            return out;
        } catch (TranslateException e) {
            throw new IllegalStateException("Failed to compute local embedding", e);
        }
    }

    private Predictor<String, float[]> ensurePredictor() {
        if (predictor != null) {
            return predictor;
        }

        initLock.lock();
        try {
            if (predictor == null) {
                Criteria<String, float[]> criteria = Criteria.builder()
                        .setTypes(String.class, float[].class)
                        .optApplication(Application.NLP.TEXT_EMBEDDING)
                        .optModelUrls("djl://ai.djl.huggingface.pytorch/" + properties.getModelId())
                    .optDevice(Device.cpu())
                        .optProgress(new ProgressBar())
                        .build();

                model = criteria.loadModel();
                predictor = model.newPredictor();
            }
            return predictor;
        } catch (ModelNotFoundException e) {
            throw new IllegalStateException("Embedding model not found: " + properties.getModelId(), e);
        } catch (MalformedModelException e) {
            throw new IllegalStateException("Embedding model is malformed: " + properties.getModelId(), e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load embedding model", e);
        } finally {
            initLock.unlock();
        }
    }

    private float[] l2Normalize(float[] vector) {
        double norm = 0.0d;
        for (float v : vector) {
            if (!Float.isFinite(v)) {
                return new float[0];
            }
            norm += (double) v * v;
        }
        if (norm == 0.0d) {
            return new float[0];
        }

        float factor = (float) (1.0d / Math.sqrt(norm));
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = vector[i] * factor;
        }
        return normalized;
    }

    private boolean isUsableVector(float[] vector) {
        if (vector == null || vector.length == 0) {
            return false;
        }

        double norm = 0.0d;
        for (float v : vector) {
            if (!Float.isFinite(v)) {
                return false;
            }
            norm += (double) v * v;
        }

        return Double.isFinite(norm) && norm > 0.0d;
    }

    @PreDestroy
    public void close() {
        if (predictor != null) {
            predictor.close();
            predictor = null;
        }
        if (model != null) {
            model.close();
            model = null;
        }
    }
}
