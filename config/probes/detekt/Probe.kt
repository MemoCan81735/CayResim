// S-005 Selbstpruefung: detekt muss hier ExplicitGarbageCollectionCall melden. Liegt ausserhalb aller Module.
package app.cayresim.probe.detekt

fun probe() {
    System.gc()
}
