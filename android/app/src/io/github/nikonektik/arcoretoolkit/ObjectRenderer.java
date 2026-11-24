package io.github.nikonektik.arcoretoolkit;

import android.opengl.GLES20;
import android.opengl.Matrix;

import com.google.ar.core.Anchor;
import com.google.ar.core.TrackingState;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

public final class ObjectRenderer {
    private static final String VERTEX_SHADER =
            "uniform mat4 u_Mvp;\n" +
            "attribute vec4 a_Position;\n" +
            "attribute vec4 a_Color;\n" +
            "varying vec4 v_Color;\n" +
            "void main() { gl_Position = u_Mvp * a_Position; v_Color = a_Color; }\n";
    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "varying vec4 v_Color;\n" +
            "void main() { gl_FragColor = v_Color; }\n";

    private static final int VERTEX_COUNT = 36;

    private int program;
    private int uMvp;
    private int aPosition;
    private int aColor;
    private FloatBuffer vertices;
    private FloatBuffer colors;

    public void createOnGlThread() {
        program = ShaderUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        uMvp = GLES20.glGetUniformLocation(program, "u_Mvp");
        aPosition = GLES20.glGetAttribLocation(program, "a_Position");
        aColor = GLES20.glGetAttribLocation(program, "a_Color");
        buildCube();
    }

    private void buildCube() {
        float s = 0.12f;
        float h = 0.24f;
        float[][] c = {
                {-s, 0, -s}, {s, 0, -s}, {s, 0, s}, {-s, 0, s},
                {-s, h, -s}, {s, h, -s}, {s, h, s}, {-s, h, s}
        };
        int[][] faces = {
                {0, 1, 2, 0, 2, 3},
                {4, 6, 5, 4, 7, 6},
                {0, 4, 5, 0, 5, 1},
                {1, 5, 6, 1, 6, 2},
                {2, 6, 7, 2, 7, 3},
                {3, 7, 4, 3, 4, 0}
        };
        float[][] faceColors = {
                {0.9f, 0.3f, 0.1f, 1f},
                {0.1f, 0.8f, 0.3f, 1f},
                {0.1f, 0.4f, 1.0f, 1f},
                {1.0f, 0.9f, 0.1f, 1f},
                {0.8f, 0.1f, 0.8f, 1f},
                {0.1f, 0.9f, 0.9f, 1f}
        };
        float[] v = new float[VERTEX_COUNT * 3];
        float[] col = new float[VERTEX_COUNT * 4];
        int vi = 0;
        int ci = 0;
        for (int f = 0; f < faces.length; f++) {
            int[] tri = faces[f];
            for (int i = 0; i < tri.length; i++) {
                float[] p = c[tri[i]];
                v[vi++] = p[0];
                v[vi++] = p[1];
                v[vi++] = p[2];
                col[ci++] = faceColors[f][0];
                col[ci++] = faceColors[f][1];
                col[ci++] = faceColors[f][2];
                col[ci++] = faceColors[f][3];
            }
        }
        vertices = makeFloatBuffer(v);
        colors = makeFloatBuffer(col);
    }

    public void draw(float[] view, float[] proj, List<Anchor> anchors) {
        if (program == 0 || anchors == null || anchors.isEmpty()) return;
        GLES20.glUseProgram(program);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);

        float[] model = new float[16];
        float[] viewModel = new float[16];
        float[] mvp = new float[16];

        synchronized (anchors) {
            for (Anchor anchor : anchors) {
                if (anchor.getTrackingState() != TrackingState.TRACKING) continue;
                anchor.getPose().toMatrix(model, 0);
                Matrix.multiplyMM(viewModel, 0, view, 0, model, 0);
                Matrix.multiplyMM(mvp, 0, proj, 0, viewModel, 0);

                GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
                GLES20.glEnableVertexAttribArray(aPosition);
                GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, vertices);
                GLES20.glEnableVertexAttribArray(aColor);
                GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_FLOAT, false, 0, colors);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, VERTEX_COUNT);
                GLES20.glDisableVertexAttribArray(aPosition);
                GLES20.glDisableVertexAttribArray(aColor);
            }
        }
    }

    private static FloatBuffer makeFloatBuffer(float[] arr) {
        FloatBuffer fb = ByteBuffer.allocateDirect(arr.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(arr);
        fb.position(0);
        return fb;
    }
}
