/**
 * This file is part of the Kompics P2P Framework.
 * 
 * Copyright (C) 2009 Swedish Institute of Computer Science (SICS)
 * Copyright (C) 2009 Royal Institute of Technology (KTH)
 *
 * Kompics is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package se.sics.gvod.p2p.orchestrator.distributed;

import java.util.TimerTask;

import se.sics.gvod.timer.Timeout;

/**
 * The <code>PeriodicTimerSignalTask</code> class.
 * 
 * @author Cosmin Arad <cosmin@sics.se>
 * @version $Id: PeriodicTimerSignalTask.java 1103 2009-08-31 13:27:58Z Cosmin $
 */
final class PeriodicTimerSignalTaskNat extends TimerTask {

	final Timeout timeout;

	private final DistributedOrchestratorNat timerComponent;

	/**
	 * Instantiates a new periodic timer signal task.
	 * 
	 * @param timeout
	 *            the timeout
	 * @param timerComponent
	 *            the timer component
	 */
	PeriodicTimerSignalTaskNat(Timeout timeout, DistributedOrchestratorNat timerComponent) {
		super();
		this.timerComponent = timerComponent;
		this.timeout = timeout;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.util.TimerTask#run()
	 */
	@Override
	public final void run() {
		timerComponent.periodicTimeout((Timeout) timeout.clone());
	}
}
