package ru.livetex.sdkui.chat.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.io.File;

import com.yalantis.ucrop.UCrop;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import ru.livetex.sdkui.R;

public final class AddFileDialog extends Dialog {
	private static final String TAG = "AddPhotoDialog";

	public interface RequestCodes {
		int CAMERA = 1000;
		int SELECT_IMAGE_OR_VIDEO = 1001;
		int SELECT_FILE = 1002;
	}

	private View cameraView;
	private View galleryView;
	private View fileView;
	private View cancelView;

	private Uri sourceFileUri;
	private Uri destFileUri;

	public AddFileDialog(@NonNull Context context) {
		super(context);

		requestWindowFeature(Window.FEATURE_NO_TITLE);
		setContentView(R.layout.d_add_file);
		WindowManager.LayoutParams wmlp = getWindow().getAttributes();
		getWindow().setBackgroundDrawable(null);
		//getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
		wmlp.gravity = Gravity.BOTTOM;
	}

	@Override
	public void show() {
		super.show();

		cameraView = findViewById(R.id.cameraView);
		galleryView = findViewById(R.id.galleryView);
		fileView = findViewById(R.id.fileView);
		cancelView = findViewById(R.id.cancelView);

		cancelView.setOnClickListener(v -> dismiss());
	}

	public void attach(Activity activity) {
		cameraView.setOnClickListener(v -> {
			Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
			intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
			intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

			File destination = new File(getAppCacheFolder(getContext()), System.currentTimeMillis() + ".jpg");

			sourceFileUri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".provider", destination);

			intent.putExtra(MediaStore.EXTRA_OUTPUT, sourceFileUri);
			activity.startActivityForResult(intent, RequestCodes.CAMERA);
		});

		galleryView.setOnClickListener(v -> {
			Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
			intent.addCategory(Intent.CATEGORY_OPENABLE);
			intent.setType("image/* video/*");
			activity.startActivityForResult(
					Intent.createChooser(intent, "Выберите изображение или видео"),
					RequestCodes.SELECT_IMAGE_OR_VIDEO);
		});

		fileView.setOnClickListener(v -> {
			Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
			intent.addCategory(Intent.CATEGORY_OPENABLE);
			intent.setType("file/*");
			activity.startActivityForResult(
					Intent.createChooser(intent, "Выберите файл"),
					RequestCodes.SELECT_FILE);
		});
	}

	public void crop(Activity activity, Uri imageUri) {
		if (imageUri == null) {
			Log.e(TAG, "crop: null image");
			return;
		}
		this.sourceFileUri = imageUri;

		File destination = new File(getAppCacheFolder(getContext()), System.currentTimeMillis() + "_cropped.jpg");
		this.destFileUri = Uri.fromFile(destination);

		UCrop.Options opt = new UCrop.Options();
		opt.setCompressionQuality(85);
		opt.setCompressionFormat(Bitmap.CompressFormat.JPEG);
		opt.setHideBottomControls(true);

		UCrop.of(imageUri, Uri.fromFile(destination))
				.withOptions(opt)
				//.withMaxResultSize(1500, 1500)
				//.withAspectRatio(3, 4)
				.start(activity);
	}

	public Uri getSourceFileUri() {
		return sourceFileUri;
	}

	public void close() {
		if (sourceFileUri != null) {
			new File(sourceFileUri.getPath()).delete();
		}

		if (destFileUri != null) {
			new File(destFileUri.getPath()).delete();
		}

		dismiss();
	}

	private String getAppCacheFolder(Context context) {
		return context.getCacheDir().getAbsolutePath();
	}
}
