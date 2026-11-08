package com.anopticlabs.gravel.weather

import android.content.Context
import android.location.LocationManager
import androidx.core.content.getSystemService
import androidx.core.location.LocationManagerCompat
import coredevices.util.Permission
import coredevices.util.PermissionRequester

class AndroidLocationAccess(
    private val context: Context,
    private val permissions: PermissionRequester,
) : LocationAccess {
    override suspend fun allowed(): Boolean {
        val manager = context.getSystemService<LocationManager>() ?: return false
        return permissions.hasPermission(Permission.Location) && LocationManagerCompat.isLocationEnabled(manager)
    }
}
