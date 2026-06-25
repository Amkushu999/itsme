// ISurfaceInjector.aidl
// Binder interface — exposed by InjectionService in the module process.
// Called from the Xposed hooks running inside the hooked target-app process.
//
// Surfaces are Parcelable and cross process boundaries transparently:
// they wrap a BufferQueue producer reference that any process can write to.
package com.itsme.amkush.ipc;

import android.view.Surface;

interface ISurfaceInjector {

    /**
     * Register a set of camera surfaces and their per-surface requirements.
     * InjectionService will attach an ImageWriter to each surface and begin
     * pushing GStreamer-decoded frames into them.
     *
     * @param surfaces  The Surface objects handed to createCaptureSession / setPreviewDisplay
     * @param widths    Per-surface output width  (index-matched to surfaces)
     * @param heights   Per-surface output height
     * @param formats   Per-surface ImageFormat constant (NV21, YUV_420_888, JPEG, etc.)
     * @param sessionId Unique tag (e.g. camera ID + timestamp) for lifecycle management
     */
    void registerSurfaces(
        in List<Surface> surfaces,
        in int[]         widths,
        in int[]         heights,
        in int[]         formats,
        String           sessionId
    );

    /** Remove all surfaces for a session and stop the associated ImageWriters. */
    void unregisterSession(String sessionId);

    /** Hard stop — tears down all sessions and the GStreamer pipeline. */
    void stopAll();
}
