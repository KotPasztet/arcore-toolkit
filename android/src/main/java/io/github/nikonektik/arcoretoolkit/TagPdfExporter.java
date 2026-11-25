package io.github.nikonektik.arcoretoolkit;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.OutputStream;

/**
 * Renders a printable A4 PDF with the AprilTag 36h11 (ID 0) drawn as vector
 * rectangles, so it stays sharp at any printer DPI.
 *
 * Layout (from third_party/apriltag/tag36h11.c, tag36h11_create()):
 *   - total_width    = 10 modules: the 8-module tag plus a 1-module white
 *     quiet zone ring on every side (module = tagSize / 8);
 *   - width_at_border = 8 modules: a 1-module black border ring plus the
 *     6x6 data field = 36 bits;
 *   - codedata[0] = 0x0000000d7e00984bUL; a "1" code bit means a WHITE
 *     module, "0" means black. Bits are consumed MSB-first and placed at
 *     (bit_x[i], bit_y[i]) inside the 8-module border grid.
 *
 * CANONICAL MATRIX (see scripts/verify_tag_matrix.py — the Python script
 * derives and re-verifies exactly this matrix with OpenCV):
 *   10x10, true = black, outer ring white (quiet zone):
 *     row0: 0 0 0 0 0 0 0 0 0 0
 *     row1: 0 1 1 1 1 1 1 1 1 0
 *     row2: 0 1 0 0 1 0 1 0 1 0
 *     row3: 0 1 1 0 0 0 1 0 1 0
 *     row4: 0 1 1 0 0 1 1 1 1 0
 *     row5: 0 1 0 1 0 1 1 1 1 0
 *     row6: 0 1 1 0 1 0 0 1 1 0
 *     row7: 0 1 1 1 1 0 1 1 1 0
 *     row8: 0 1 1 1 1 1 1 1 1 0
 *     row9: 0 0 0 0 0 0 0 0 0 0
 */
public final class TagPdfExporter {
    private static final int A4_WIDTH_PT = 595;   // 210 mm
    private static final int A4_HEIGHT_PT = 842;  // 297 mm
    private static final float PT_PER_MM = 72f / 25.4f;
    private static final int MAX_TAG_MM = 160;    // 1.25 * tag must fit A4 width
    private static final int SCALE_BAR_MM = 100;

    /** Tag sizes the print dialog accepts; export itself clamps to MAX_TAG_MM. */
    static final int MIN_TAG_MM = 40;
    static final int MAX_REQUESTED_TAG_MM = 300;

    /** Chosen tag size, shared by the PDF export and the marker distance math. */
    public static final String PREFS_NAME = "arcoretoolkit";
    public static final String PREF_TAG_SIZE_MM = "tag_size_mm";
    public static final int DEFAULT_TAG_MM = 150;
    /** Real printed tags tend to be slightly small; pose math applies the same factor. */
    public static final double PRINT_SCALE = 0.99;

    private static final String TAG = "TagPdf";

    /** 10x10 grid for tag36h11 ID 0; true = black; includes the white quiet ring. */
    static final boolean[][] TAG36H11_ID0 = {
            {false, false, false, false, false, false, false, false, false, false},
            {false, true,  true,  true,  true,  true,  true,  true,  true,  false},
            {false, true,  false, false, true,  false, true,  false, true,  false},
            {false, true,  true,  false, false, false, true,  false, true,  false},
            {false, true,  true,  false, false, true,  true,  true,  true,  false},
            {false, true,  false, true,  false, true,  true,  true,  true,  false},
            {false, true,  true,  false, true,  false, false, true,  true,  false},
            {false, true,  true,  true,  true,  false, true,  true,  true,  false},
            {false, true,  true,  true,  true,  true,  true,  true,  true,  false},
            {false, false, false, false, false, false, false, false, false, false},
    };

    /** Outcome of {@link #export}. */
    public static final class Result {
        public final Uri uri;
        public final String fileName;
        public final String error;
        public final int errorRes;

        Result(Uri uri, String fileName, String error, int errorRes) {
            this.uri = uri;
            this.fileName = fileName;
            this.error = error;
            this.errorRes = errorRes;
        }
    }

    /** Tag size chosen in the print dialog, in mm. */
    public static int getTagSizeMm(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(PREF_TAG_SIZE_MM, DEFAULT_TAG_MM);
    }

    /** Outer black square size in metres (print scale included) for pose math. */
    public static double tagSizeMetres(Context context) {
        return getTagSizeMm(context) / 1000.0 * PRINT_SCALE;
    }

    /**
     * Print-tag dialog shared by the main screen and MarkerActivity: asks for
     * the tag size, renders the PDF on a worker thread, saves it to Downloads
     * and opens the share sheet.
     */
    public static void showPrintDialog(final Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView explainer = new TextView(activity);
        explainer.setText(activity.getString(R.string.marker_print_explainer));
        explainer.setTextSize(13f);
        box.addView(explainer);

        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(activity.getString(R.string.marker_print_hint));
        input.setText(String.valueOf(getTagSizeMm(activity)));
        input.setSelection(input.getText().length());
        box.addView(input);

        new MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.marker_print_title))
                .setView(box)
                .setPositiveButton(activity.getString(R.string.marker_print_create),
                        (d, w) -> {
                            int mm;
                            try {
                                mm = Integer.parseInt(input.getText().toString().trim());
                            } catch (NumberFormatException e) {
                                mm = -1;
                            }
                            if (mm < MIN_TAG_MM || mm > MAX_REQUESTED_TAG_MM) {
                                Toast.makeText(activity,
                                        activity.getString(R.string.marker_print_toast_invalid),
                                        Toast.LENGTH_LONG).show();
                                return;
                            }
                            exportAndShare(activity, mm);
                        })
                .setNegativeButton(activity.getString(R.string.common_cancel), null)
                .show();
    }

    private static void exportAndShare(final Activity activity, final int tagSizeMm) {
        Toast.makeText(activity, activity.getString(R.string.marker_print_toast_working),
                Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final Result result = export(activity, tagSizeMm);
            activity.runOnUiThread(() -> {
                if (result.uri != null) {
                    activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putInt(PREF_TAG_SIZE_MM, tagSizeMm).apply();
                    Toast.makeText(activity,
                            activity.getString(R.string.marker_print_toast_saved, result.fileName),
                            Toast.LENGTH_LONG).show();
                    try {
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType("application/pdf");
                        share.putExtra(Intent.EXTRA_STREAM, result.uri);
                        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        activity.startActivity(Intent.createChooser(share,
                                activity.getString(R.string.marker_print_share_chooser)));
                    } catch (Throwable t) {
                        ToolkitLog.w(TAG, "share failed", t);
                    }
                } else if (result.errorRes > 0) {
                    Toast.makeText(activity, activity.getString(result.errorRes, tagSizeMm),
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(activity,
                            activity.getString(R.string.marker_print_toast_failed, result.error),
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "tag-pdf-export").start();
    }

    private TagPdfExporter() {}

    /**
     * Renders and saves the PDF into MediaStore.Downloads. Runs on a worker
     * thread (no I/O on the UI thread).
     */
    public static Result export(Context context, int tagSizeMm) {
        if (tagSizeMm < MIN_TAG_MM) {
            return new Result(null, null, null, R.string.marker_print_toast_invalid);
        }
        // The 10-module block (tag + quiet ring) must fit the 210 mm page width.
        if (tagSizeMm > MAX_TAG_MM) {
            return new Result(null, null, null, R.string.marker_print_too_large);
        }
        String fileName = "arcore-toolkit-tag-a4.pdf";
        PdfDocument doc = new PdfDocument();
        Uri uri = null;
        try {
            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(
                    A4_WIDTH_PT, A4_HEIGHT_PT, 1).create();
            PdfDocument.Page page = doc.startPage(info);
            drawPage(context, page.getCanvas(), tagSizeMm);
            doc.finishPage(page);

            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
            uri = context.getContentResolver()
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return new Result(null, fileName, "MediaStore insert failed", 0);
            }
            try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new IllegalStateException("openOutputStream returned null");
                doc.writeTo(os);
            }
            return new Result(uri, fileName, null, 0);
        } catch (Throwable t) {
            if (uri != null) {
                try {
                    context.getContentResolver().delete(uri, null, null);
                } catch (Throwable ignored) {}
            }
            return new Result(null, fileName, String.valueOf(t), 0);
        } finally {
            doc.close();
        }
    }

    /** One vector A4 page: tag, scale bar, caption, print instructions. */
    private static void drawPage(Context context, Canvas canvas, int tagSizeMm) {
        Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
        white.setColor(Color.WHITE);
        canvas.drawRect(0, 0, A4_WIDTH_PT, A4_HEIGHT_PT, white);

        Paint black = new Paint(Paint.ANTI_ALIAS_FLAG);
        black.setColor(Color.BLACK);
        black.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));

        float module = tagSizeMm * PT_PER_MM / 8f;   // outer black square = 8 modules
        float block = 10 * module;                    // tag + 1-module white ring
        float x0 = (A4_WIDTH_PT - block) / 2f;
        float y0 = 50f;

        for (int row = 0; row < 10; row++) {
            for (int col = 0; col < 10; col++) {
                if (TAG36H11_ID0[row][col]) {
                    canvas.drawRect(x0 + col * module, y0 + row * module,
                            x0 + (col + 1) * module, y0 + (row + 1) * module, black);
                }
            }
        }

        // 100 mm scale bar with end ticks, exactly SCALE_BAR_MM long when printed.
        float barLen = SCALE_BAR_MM * PT_PER_MM;
        float barY = y0 + block + 32f;
        float barX = x0;
        canvas.drawRect(barX, barY - 1f, barX + barLen, barY + 1f, black);
        canvas.drawRect(barX - 1f, barY - 6f, barX + 1f, barY + 6f, black);
        canvas.drawRect(barX + barLen - 1f, barY - 6f, barX + barLen + 1f, barY + 6f, black);

        black.setTextAlign(Paint.Align.CENTER);
        black.setTextSize(11f);
        canvas.drawText(context.getString(R.string.tagpdf_scale_bar),
                barX + barLen / 2f, barY + 22f, black);

        black.setTextSize(11f);
        black.setFakeBoldText(true);
        canvas.drawText(context.getString(R.string.tagpdf_caption, tagSizeMm),
                A4_WIDTH_PT / 2f, barY + 46f, black);

        black.setFakeBoldText(false);
        black.setTextSize(9f);
        canvas.drawText(context.getString(R.string.tagpdf_instructions_1),
                A4_WIDTH_PT / 2f, barY + 64f, black);
        canvas.drawText(context.getString(R.string.tagpdf_instructions_2),
                A4_WIDTH_PT / 2f, barY + 78f, black);
    }
}
