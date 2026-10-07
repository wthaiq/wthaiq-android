package com.wthaiq.signing

import androidx.core.content.FileProvider

/**
 * Shares photos taken for an ID attachment with the camera app. A subclass, so it never clashes with a
 * `FileProvider` declared by the host app.
 */
public class WthaiqSigningFileProvider : FileProvider(R.xml.wthaiq_signing_file_paths)
