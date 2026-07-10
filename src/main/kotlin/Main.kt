import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.terminologies.AdministrativeGender
import kotlinx.io.Buffer
import kotlinx.io.writeString
import org.cqframework.cql.cql2elm.CqlCompilerOptions
import org.cqframework.cql.cql2elm.LibraryContentType
import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.cql.cql2elm.LibrarySourceProvider
import org.cqframework.cql.cql2elm.ModelManager
import org.cqframework.cql.shared.QName
import org.hl7.cql.model.ModelIdentifier
import org.hl7.cql.model.ModelInfoProvider
import org.hl7.elm.r1.VersionedIdentifier
import org.hl7.elm_modelinfo.r1.ModelInfo
import org.hl7.elm_modelinfo.r1.serializing.parseModelInfoXml
import org.opencds.cqf.cql.engine.execution.CqlEngine
import org.opencds.cqf.cql.engine.execution.Environment
import org.opencds.cqf.cql.engine.runtime.ClassInstance
import org.opencds.cqf.cql.engine.runtime.Value
import org.opencds.cqf.cql.engine.runtime.toCqlString
import kotlin.reflect.full.memberProperties
import dev.ohs.fhir.model.r4.Date as FhirDateWrapper
import dev.ohs.fhir.model.r4.FhirDate
import org.opencds.cqf.cql.engine.runtime.Date as EngineDate

private const val FHIR = "http://hl7.org/fhir"

private val FHIR_CQL = """
    library Probe version '1.0.0'
    using FHIR version '4.0.1'
    parameter "P" FHIR.Patient
    define "GenderValue": P.gender.value
    define "BirthDateValue": P.birthDate.value
""".trimIndent()

/**
 * Increment 5a — the general walker's skeleton. Reflection enumerates the resource's
 * properties at runtime; the `when` maps each value by its runtime type. One mapping
 * row exists so far (Enumeration -> FHIR.code); anything unmapped prints a TODO —
 * the literal work-list for increments 5c..5f.
 */
private fun toClassInstance(resource: Any, fhirTypeName: String): ClassInstance {
    val elements = mutableMapOf<String, Value?>()
    for (prop in resource::class.memberProperties) {
        val raw = prop.getter.call(resource) ?: continue        // unset field -> absent
        if (raw is Collection<*> && raw.isEmpty()) continue     // kotlin-fhir lists default to empty -> absent
        when (raw) {
            is Enumeration<*> -> {
                val code = raw.value?.let { it::class.java.getMethod("getCode").invoke(it) as String }
                if (code != null) {
                    elements[prop.name] =
                        ClassInstance(QName(FHIR, "code"), mutableMapOf<String, Value?>("value" to code.toCqlString()))
                }
            }
            is FhirDateWrapper -> {                        // FHIR primitive `date`
                raw.value?.let { fhirDate ->                // sealed: Year | YearMonth | Date
                    // ISO toString + the engine's precision-inferring string ctor:
                    // "1985" -> YEAR, "1985-03" -> MONTH, "1985-03-14" -> DAY
                    elements[prop.name] = ClassInstance(
                        QName(FHIR, "date"),
                        mutableMapOf<String, Value?>("value" to EngineDate(fhirDate.toString())),
                    )
                }
            }
            else -> println("  TODO: no mapping row yet for ${prop.name} (${raw::class.simpleName})")
        }
    }
    return ClassInstance(QName(FHIR, fhirTypeName), elements)
}

private class InlineCqlSource(private val id: String, private val cql: String) : LibrarySourceProvider {
    override fun getLibrarySource(libraryIdentifier: VersionedIdentifier) =
        if (libraryIdentifier.id == id) Buffer().apply { writeString(cql) } else null
    override fun getLibraryContent(libraryIdentifier: VersionedIdentifier, type: LibraryContentType) =
        if (type == LibraryContentType.CQL) getLibrarySource(libraryIdentifier) else null
}

private class FhirModelInfoProvider(private val xml: String) : ModelInfoProvider {
    override fun load(modelIdentifier: ModelIdentifier): ModelInfo? =
        if (modelIdentifier.id == "FHIR") parseModelInfoXml(Buffer().apply { writeString(xml) }) else null
}

fun main() {
    val modelInfoXml = object {}.javaClass.classLoader
        .getResourceAsStream("fhir-modelinfo-4.0.1.xml")!!.bufferedReader().readText()

    val options = CqlCompilerOptions().apply {
        setOptions(CqlCompilerOptions.Options.EnableResultTypes, CqlCompilerOptions.Options.EnableLocators)
    }
    val modelManager = ModelManager().apply {
        modelInfoLoader.registerModelInfoProvider(FhirModelInfoProvider(modelInfoXml))
    }
    val libraryManager = LibraryManager(modelManager, options).apply {
        librarySourceLoader.registerProvider(InlineCqlSource("Probe", FHIR_CQL))
    }
    val engine = CqlEngine(Environment(libraryManager), mutableSetOf(CqlEngine.Options.EnableTypeChecking))

    // A REAL kotlin-fhir R4 Patient — the 'female' now originates from a live FHIR model object,
    // not a string literal. THIS is what increment 4 adds over increment 3.
    val fhirPatient = Patient(
			gender = Enumeration.of(AdministrativeGender.Female, null),
			birthDate = FhirDateWrapper(value = FhirDate.fromString("1985")),
			)
    println("kotlin-fhir Patient.gender.value.getCode() = ${fhirPatient.gender?.value?.getCode()}")
    val patientCI = toClassInstance(fhirPatient, "Patient")

    val probe = VersionedIdentifier().withId("Probe").withVersion("1.0.0")
    val results = engine.evaluate {
        library(probe) { expressions("GenderValue", "BirthDateValue") }
        parameters = mapOf("P" to patientCI)
    }.onlyResultOrThrow
    println("v5 engine evaluated P.gender.value    = ${results["GenderValue"]?.value}   (expect: 'female')")
    println("v5 engine evaluated P.birthDate.value = ${results["BirthDateValue"]?.value}   (expect: 1985-03-14)")
}
