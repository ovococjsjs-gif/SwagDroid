package com.swagdroid.feature.cleaner.safety

import androidx.core.content.FileProvider

/** Not exported. Only an explicitly selected, revalidated file receives a temporary read grant. */
class CleanerPreviewProvider : FileProvider()
