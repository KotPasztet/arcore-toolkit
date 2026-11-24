package io.github.nikonektik.arcoretoolkit;

import android.opengl.GLES20;
import android.opengl.Matrix;

import com.google.ar.core.Plane;
import com.google.ar.core.TrackingState;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collection;

public final class PlaneRenderer {
    private static final String VERTEX_SHADER =
            "uniform mat4 u_Mvp;\n" +
            "attribute vec4 a_Position;\n" +
            "void main() { gl_Position = u_Mvp * a_Position; }\n";
    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "uniform vec4 u_Color;\n" +
            "void main() { gl_FragColor = u_Color; }\n";

    private int program;
    private int uMvp;
    private int uColor;
    private int aPosition;

    public void createOnGlThread() {
        program = ShaderUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        uMvp = GLES20.glGetUniformLocation(program, "u_Mvp");
        uColor = GLES20.glGetUniformLocation(program, "u_Color");
        aPosition = GLES20.glGetAttribLocation(program, "a_Position");
    }

    public void draw(Collection<Plane> planes, float[] view, float[] proj) {
        if (program == 0 || planes == null) return;
        GLES20.glUseProgram(program);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glLineWidth(4f);

        float[] model = new float[16];
        float[] viewModel = new float[16];
        float[] mvp = new float[16];

        for (Plane plane : planes) {
            if (plane.getTrackingState() != TrackingState.TRACKING) continue;
            if (plane.getSubsumedBy() != null) continue;
            FloatBuffer polygon = plane.getPolygon();
            if (polygon == null) continue;
            polygon.rewind();
            int count = polygon.remaining() / 2;
            if (count < 3) continue;

            float[] verts = new float[count * 3];
            for (int i = 0; i < count; i++) {
                verts[i * 3] = polygon.get();
                verts[i * 3 + 1] = 0f;
                verts[i * 3 + 2] = polygon.get();
            }
            FloatBuffer vb = ByteBuffer.allocateDirect(verts.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            vb.put(verts);
            vb.position(0);

            plane.getCenterPose().toMatrix(model, 0);
            Matrix.multiplyMM(viewModel, 0, view, 0, model, 0);
            Matrix.multiplyMM(mvp, 0, proj, 0, viewModel, 0);

            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
            GLES20.glUniform4f(uColor, 0.1f, 1.0f, 0.4f, 0.85f);
            GLES20.glEnableVertexAttribArray(aPosition);
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, vb);
            GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, count);
            GLES20.glDisableVertexAttribArray(aPosition);
        }
    }
}
