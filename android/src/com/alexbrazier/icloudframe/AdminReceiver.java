package com.alexbrazier.icloudframe;

import android.app.admin.DeviceAdminReceiver;

/** Needed for `dpm set-device-owner`; the policies themselves are applied in Device. */
public final class AdminReceiver extends DeviceAdminReceiver {
}
