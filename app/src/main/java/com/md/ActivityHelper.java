package com.md;

import java.io.File;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;

import com.md.modesetters.BrowsingModeSetter;
import com.md.modesetters.CleanUpAudioFilesModeSetter;
import com.md.modesetters.CreateModeSetter;
import com.md.modesetters.DeckChooseModeSetter;
import com.md.modesetters.LearningModeSetter;
import com.md.modesetters.ModeSetter;
import com.md.modesetters.SettingModeSetter;
import com.md.utils.ToastSingleton;

public class ActivityHelper {

	TimerManager timerManager = new TimerManager();

	public void commonActivitySetup(Activity activity) {
		File theFile = new File(DbContants.getDatabasePath());
		File parentFile = new File(theFile.getParent());


		if (!parentFile.exists()) {
			parentFile.mkdirs();
		}

		DbNoteEditor.getInstance().setContext(activity);
		ToastSingleton.getInstance().setContext(activity);

		// Init the db with this:
		DbNoteEditor.getInstance().getFirst();
	}

	public void createCommonMenu(Menu menu, final SpacedRepeaterActivity activity) {
		MenuInflater inflater = activity.getMenuInflater();
		inflater.inflate(R.menu.menu, menu);

		MenuItem quitMenuItem = menu.findItem(R.id.dimMenuItem);
		quitMenuItem.setOnMenuItemClickListener(new OnMenuItemClickListener() {
			@Override
			public boolean onMenuItemClick(MenuItem item) {
				activity.maybeDim();
				return true;
			}
		});

		menu.findItem(R.id.backup).setOnMenuItemClickListener(new OnMenuItemClickListener() {
			@Override
			public boolean onMenuItemClick(MenuItem item) {
				BackupToUsbManager.INSTANCE.openZipFileDocument(activity);
				return true;
			}
		});

		menu.findItem(R.id.large_timer).setOnMenuItemClickListener(new OnMenuItemClickListener() {
			@Override
			public boolean onMenuItemClick(MenuItem item) {
				timerManager.addTimer(20);
				return true;
			}
		});

		menu.findItem(R.id.small_timer).setOnMenuItemClickListener(new OnMenuItemClickListener() {
			@Override
			public boolean onMenuItemClick(MenuItem item) {
				timerManager.addTimer(7);
				return true;
			}
		});

		addMenu(menu, R.id.creationModeMenuItem, CreateModeSetter.getInstance(), activity);
		addMenu(menu, R.id.browseDeckModeMenuItem, BrowsingModeSetter.getInstance(), activity);
		addMenu(menu, R.id.learningModeMenuItem, LearningModeSetter.getInstance(), activity);
		addMenu(menu, R.id.selectDeckModeMenuItem, DeckChooseModeSetter.getInstance(), activity);
		addMenu(menu, R.id.selectDeckModeMenuItem, DeckChooseModeSetter.getInstance(), activity);
		addMenu(menu, R.id.settings, SettingModeSetter.getInstance(), activity);
		addMenu(menu, R.id.clean_up_files, CleanUpAudioFilesModeSetter.getInstance(), activity);
	}

	private void addMenu(Menu menu, int item, final ModeSetter ms,
			Activity activity) {
		MenuItem findItem = menu.findItem(item);

		final Activity context = activity;

		findItem.setOnMenuItemClickListener(new OnMenuItemClickListener() {
			@Override
			public boolean onMenuItemClick(MenuItem item) {
				if (ms instanceof CreateModeSetter) {
					((CreateModeSetter) ms).setNote(null);
				}

				ms.setupMode(context);

				return true;
			}
		});
	}
}
