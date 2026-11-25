package io.github.nikonektik.arcoretoolkit;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.util.Size;
import android.util.SizeF;

/**
 * Back-camera characteristics plus the intrinsics ARCore expects for its
 * 640x480 CPU image.
 *
 * <p>If the stream uses the full sensor width, {@code fx = f_mm / sensor_width_mm
 * * 640}.  This is the number a device profile must contain; it can be verified
 * with a printed target (see docs/04-calibration.md).
 */
public final class CameraSpec {
    public final String cameraId;
    public final SizeF physicalSize;
    public final float focalLength;
    public final Rect activeArray;
    public final Size pixelArray;
    public final Integer sensorOrientation;
    public final Integer timestampSource;
    public final float[] calibration;
    public final float[] distortion;

    private CameraSpec(String cameraId, SizeF physicalSize, float focalLength,
                             Rect activeArray, Size pixelArray, Integer sensorOrientation,
                             Integer timestampSource, float[] calibration, float[] distortion) {
        this.cameraId = cameraId;
        this.physicalSize = physicalSize;
        this.focalLength = focalLength;
        this.activeArray = activeArray;
        this.pixelArray = pixelArray;
        this.sensorOrientation = sensorOrientation;
        this.timestampSource = timestampSource;
        this.calibration = calibration;
        this.distortion = distortion;
    }

    /** Reads the first back-facing camera, or returns {@code null}. */
    public static CameraSpec read(Context context) {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) {
                    continue;
                }
                float[] focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                return new CameraSpec(
                        id,
                        c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE),
                        focals != null && focals.length > 0 ? focals[0] : 0f,
                        c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
                        c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE),
                        c.get(CameraCharacteristics.SENSOR_ORIENTATION),
                        c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE),
                        c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION),
                        c.get(CameraCharacteristics.LENS_DISTORTION));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public boolean valid() {
        return physicalSize != null
                && physicalSize.getWidth() > 0f
                && physicalSize.getHeight() > 0f
                && focalLength > 0f;
    }

    public float fx() {
        return focalLength / physicalSize.getWidth() * 640f;
    }

    public float fy() {
        return focalLength / physicalSize.getHeight() * 480f;
    }

    public float hfovDeg() {
        return (float) Math.toDegrees(2.0 * Math.atan(physicalSize.getWidth() / (2.0 * focalLength)));
    }

    public float vfovDeg() {
        return (float) Math.toDegrees(2.0 * Math.atan(physicalSize.getHeight() / (2.0 * focalLength)));
    }
}
