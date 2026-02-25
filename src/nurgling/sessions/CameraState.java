package nurgling.sessions;

import haven.Coord3f;

/**
 * Holds camera state for syncing across sessions.
 * Different camera types use different fields.
 */
public class CameraState {
    /** Camera type class name (e.g., "FollowCam", "FreeCam") */
    public final String cameraType;

    /** Zoom value (telev for FollowCam, tdist for FreeCam, tfield for SOrthoCam) */
    public final float zoom;

    /** Rotation angle (tangl - common across all camera types) */
    public final float rotation;

    /** Camera center position (only used for FreeCam) */
    public final Coord3f position;

    /**
     * Create camera state with all fields.
     */
    public CameraState(String cameraType, float zoom, float rotation, Coord3f position) {
        this.cameraType = cameraType;
        this.zoom = zoom;
        this.rotation = rotation;
        this.position = position;
    }

    /**
     * Create camera state without position (for FollowCam, SOrthoCam).
     */
    public CameraState(String cameraType, float zoom, float rotation) {
        this(cameraType, zoom, rotation, null);
    }
}
