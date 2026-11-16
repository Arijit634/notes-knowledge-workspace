package org.notesknowledge;

import java.time.Duration;

/** Owner-module projection for a cost optimization, never an authorization decision. */
public interface DatabaseIdleParticipant {
    String owner();
    Forecast inspect(long activityVersion);

    record Forecast(boolean quiet, Duration nextMaintenance) {
        public Forecast {
            if(nextMaintenance==null||nextMaintenance.isNegative())throw new IllegalArgumentException("Invalid maintenance forecast");
        }
        public static Forecast busy(){return new Forecast(false,Duration.ZERO);}
        public static Forecast quiet(Duration next){return new Forecast(true,next);}
    }
}
