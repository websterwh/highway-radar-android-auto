// IHighwayRadarAlertListener.aidl
//
// Shared interop contract between this module and the Android Auto companion app
// (com.highwayradar.aa.companion). The relay currently transports the payload over an explicit
// broadcast — action com.highwayradar.aa.ALERTS_UPDATED, extra key alerts_json — because the
// data originates inside Highway Radar's own process, which this module cannot host a bound
// service in. This interface pins the payload signature so both sides implement exactly one
// shape: a JSON array of alert objects, as produced by AlertExtractor.
//
// The module itself does not bind or expose this interface; it exists as the contract the
// companion side codes against. A bound-service transport can be added later without changing
// the payload.

package com.highwayradar.aa.idl;

interface IHighwayRadarAlertListener {
    oneway void onAlertsUpdated(String alertsJson);
}