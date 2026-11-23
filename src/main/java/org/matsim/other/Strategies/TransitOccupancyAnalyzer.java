package org.matsim.other.Strategies;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.PersonLeavesVehicleEventHandler;
import org.matsim.vehicles.Vehicle;

import java.util.HashMap;
import java.util.Map;

public class TransitOccupancyAnalyzer implements PersonEntersVehicleEventHandler, PersonLeavesVehicleEventHandler {

    private final Map<Id<Vehicle>, Integer> occupancyByVehicle = new HashMap<>();

    @Override
    public void handleEvent(PersonEntersVehicleEvent event) {
        occupancyByVehicle.merge(event.getVehicleId(), 1, Integer::sum);
    }

    @Override
    public void handleEvent(PersonLeavesVehicleEvent event) {
        occupancyByVehicle.merge(event.getVehicleId(), -1, Integer::sum);
    }

    @Override
    public void reset(int iteration) {
        occupancyByVehicle.clear();
    }

    public int getOccupancy(Id<Vehicle> vehicleId) {
        return occupancyByVehicle.getOrDefault(vehicleId, 0);
    }
}
