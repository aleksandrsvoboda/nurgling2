/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven;

/**
 * Listener for UI lifecycle events.
 * Used by extensions for multi-session support.
 */
public interface UILifecycleListener {
    /**
     * Called before creating a new UI.
     * @param runner The runner that will use the UI
     * @param currentUI The current UI (may be null)
     * @param panel The UIPanel creating the UI
     * @return non-null UI to reuse instead of creating new, null to proceed normally
     */
    default UI beforeNewUI(UI.Runner runner, UI currentUI, UIPanel panel) {
        return null;
    }

    /**
     * Called after new UI created but before old UI destroyed.
     * @param newUI The newly created UI
     * @param oldUI The previous UI (may be null)
     * @return true to destroy oldUI, false to keep it (e.g., demote to headless)
     */
    default boolean afterNewUI(UI newUI, UI oldUI) {
        return true;
    }

    /**
     * Called when a new session is requested (e.g., "Add Account" button).
     * @param panel The UIPanel handling the request
     */
    default void onNewSessionRequested(UIPanel panel) {
    }
}
