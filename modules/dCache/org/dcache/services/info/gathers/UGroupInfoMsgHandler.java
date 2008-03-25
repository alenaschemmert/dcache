package org.dcache.services.info.gathers;

import org.apache.log4j.Logger;
import org.dcache.services.info.base.StatePath;
import org.dcache.services.info.base.StateUpdate;


/**
 * Process an incoming message from PoolManager about a specific UnitGroup.
 * 
 * @author Paul Millar <paul.millar@desy.de>
 */
public class UGroupInfoMsgHandler extends CellMessageHandlerSkel {

	private static Logger _log = Logger.getLogger( UGroupInfoMsgHandler.class);
	
	private static final StatePath UNITGROUP_PATH = new StatePath( "unitgroups");
	
	public void process(Object msgPayload, long metricLifetime) {
		
		Object array[];
		
		StateUpdate update = new StateUpdate();
		
		if( !msgPayload.getClass().isArray()) {
			_log.error( "unexpected received non-array payload");
			return;			
		}
		
		array = (Object []) msgPayload;
		
		if( array.length != 3) {
			_log.error( "unexpected array size: "+array.length);
			return;
		}
		
		/**
		 * array[0] = group name
		 * array[1] = unit list
		 * array[2] = link list
		 */
		String unitGroupName = (String) array[0];
		
		StatePath thisUGroupPath = UNITGROUP_PATH.newChild( unitGroupName);

		addItems( update, thisUGroupPath.newChild("units"), (Object []) array [1], metricLifetime);
		addItems( update, thisUGroupPath.newChild("links"), (Object []) array [2], metricLifetime);
		
		applyUpdates( update);
	}

}
