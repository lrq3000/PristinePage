import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

/**
 * Candidate runtime bridge, compiled for qualification only; not in an Android source set. Invoke off the UI thread only after the exact asset, backend, resource budget, and merged-manifest policy are admitted. A caller retains its asset lease and buffer ownership for the whole call.
 */
public final class OrtDocQuadCpu {
    private static final String INPUT = "input";
    private static final String MASK = "mask_logits";
    private static final String HEATMAPS = "corner_heatmaps";
    private static final long[] INPUT_SHAPE = {1, 3, 256, 256};
    private static final long[] MASK_SHAPE = {1, 1, 64, 64};
    private static final long[] HEATMAP_SHAPE = {1, 4, 64, 64};

    private OrtDocQuadCpu() {}

    /** Returns the detached mask followed by the TL/TR/BR/BL heatmap planes. */
    public static float[][] run(Path verifiedLeasedAsset, float[] input, Runnable checkpoint) throws OrtException {
        if (input.length != 3 * 256 * 256) throw new IllegalArgumentException("Unexpected DocQuad input extent");
        for (int i = 0; i < input.length; i++) {
            if (i % (256 * 256) == 0) checkpoint.run();
            if (!(input[i] >= 0 && input[i] <= 1)) throw new IllegalArgumentException("Invalid DocQuad input sample");
        }
        checkpoint.run();
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        environment.setTelemetry(false);
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            // No delegate registration: use the default CPU provider. The private store does not use format suffixes.
            options.addConfigEntry("session.load_model_format", "ORT");
            checkpoint.run();
            try (OrtSession session = environment.createSession(verifiedLeasedAsset.toString(), options)) {
                inspect(session);
                checkpoint.run();
                try (OnnxTensor tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), INPUT_SHAPE);
                     OrtSession.Result result = session.run(Collections.singletonMap(INPUT, tensor))) {
                    checkpoint.run();
                    if (result.size() != 2) throw new IllegalArgumentException("Unexpected DocQuad output count");
                    float[] mask = copy(result, MASK, MASK_SHAPE, 64 * 64);
                    checkpoint.run();
                    float[] heatmaps = copy(result, HEATMAPS, HEATMAP_SHAPE, 4 * 64 * 64);
                    checkpoint.run();
                    return new float[][] {mask, heatmaps};
                }
            }
        }
    }

    private static void inspect(OrtSession session) throws OrtException {
        Map<String, NodeInfo> inputs = session.getInputInfo();
        Map<String, NodeInfo> outputs = session.getOutputInfo();
        if (inputs.size() != 1 || outputs.size() != 2) throw new IllegalArgumentException("Unexpected DocQuad graph ports");
        checkInfo(inputs.get(INPUT), INPUT_SHAPE);
        checkInfo(outputs.get(MASK), MASK_SHAPE);
        checkInfo(outputs.get(HEATMAPS), HEATMAP_SHAPE);
    }

    private static void checkInfo(NodeInfo node, long[] shape) {
        if (node == null || !(node.getInfo() instanceof TensorInfo))
            throw new IllegalArgumentException("Missing DocQuad tensor");
        checkTensor((TensorInfo) node.getInfo(), shape);
    }

    private static float[] copy(OrtSession.Result result, String name, long[] shape, int size) {
        OnnxValue value = result.get(name).orElseThrow(() -> new IllegalArgumentException("Missing DocQuad output"));
        if (!(value instanceof OnnxTensor)) throw new IllegalArgumentException("Unexpected DocQuad output type");
        OnnxTensor tensor = (OnnxTensor) value;
        checkTensor(tensor.getInfo(), shape);
        FloatBuffer buffer = tensor.getFloatBuffer();
        if (buffer == null || buffer.remaining() != size) throw new IllegalArgumentException("Unexpected DocQuad output extent");
        float[] samples = new float[size];
        buffer.get(samples);
        for (float sample : samples) if (!Float.isFinite(sample)) throw new IllegalArgumentException("Nonfinite DocQuad output");
        return samples;
    }

    private static void checkTensor(TensorInfo info, long[] shape) {
        if (info.type != OnnxJavaType.FLOAT || !Arrays.equals(info.getShape(), shape))
            throw new IllegalArgumentException("Unexpected DocQuad tensor contract");
    }
}
