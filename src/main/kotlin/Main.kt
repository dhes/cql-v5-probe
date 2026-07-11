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
import dev.ohs.fhir.model.r4.String as FhirString
import org.opencds.cqf.cql.engine.runtime.List as CqlList
import dev.ohs.fhir.model.r4.HumanName

private const val FHIR = "http://hl7.org/fhir"

private val FHIR_CQL = """
    library Probe version '1.0.0'
    using FHIR version '4.0.1'
    parameter "P" FHIR.Patient
    define "GenderValue": P.gender.value
    define "BirthDateValue": P.birthDate.value
    define "FamilyNameValue": First(P.name.family.value)
""".trimIndent()

/**
 * Convert ONE kotlin-fhir value to the engine Value it maps to. Null = no mapping row yet.
 * Expression-style: every row ENDS with its result; nothing is assigned here — the caller
 * decides where converted values go.
 */
private fun convertValue(raw: Any): Value? = when (raw) {
    // FHIR primitive `code` (required-binding enum): unwrap to the wire-format code string.
    // The generated enums share no common interface, so getCode() is looked up reflectively.
    is Enumeration<*> -> {
        val code = raw.value?.let { it::class.java.getMethod("getCode").invoke(it) as String }
        code?.let { ClassInstance(QName(FHIR, "code"), mutableMapOf<String, Value?>("value" to it.toCqlString())) }
    }

    // FHIR primitive `date`: every sealed FhirDate variant (Year | YearMonth | Date) has an
    // ISO toString, and the engine's string ctor infers precision from the segment count
    // ("1985" -> YEAR, "1985-03" -> MONTH, "1985-03-14" -> DAY) — partial precision survives.
    is FhirDateWrapper -> raw.value?.let { fhirDate ->
        ClassInstance(QName(FHIR, "date"), mutableMapOf<String, Value?>("value" to EngineDate(fhirDate.toString())))
    }

    // FHIR primitive `string`.
    is FhirString -> raw.value?.let {
        ClassInstance(QName(FHIR, "string"), mutableMapOf<String, Value?>("value" to it.toCqlString()))
    }

    // Complex types delegate back to the property walker — MUTUAL recursion.
    is HumanName -> toClassInstance(raw, "HumanName")

    // Lists: convert each element through this same dispatch — SELF recursion.
    is List<*> -> CqlList(raw.map { element -> element?.let { convertValue(it) } })

    else -> null
}

/**
 * Walk ONE resource's properties via reflection and build its ClassInstance.
 * Conversion of each value is delegated to convertValue (which recurses back
 * here for complex types like HumanName).
 */
private fun toClassInstance(resource: Any, fhirTypeName: String): ClassInstance {
    val elements = mutableMapOf<String, Value?>()
    for (prop in resource::class.memberProperties) {
        val raw = prop.getter.call(resource) ?: continue        // unset field -> absent
        if (raw is Collection<*> && raw.isEmpty()) continue     // kotlin-fhir lists default to empty -> absent
        val converted = convertValue(raw)
        if (converted != null) elements[prop.name] = converted
        else println("  TODO: no mapping row yet for ${prop.name} (${raw::class.simpleName})")
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

    // A REAL kotlin-fhir R4 Patient — gender, a YEAR-precision birthDate, and a name.
    // Everything the engine sees is produced by the reflection walker above.
    val fhirPatient = Patient(
        gender = Enumeration.of(AdministrativeGender.Female, null),
        birthDate = FhirDateWrapper(value = FhirDate.fromString("1985")),
        name = listOf(HumanName(family = FhirString(value = "Berfel"))),
    )
    println("kotlin-fhir Patient.gender.value.getCode() = ${fhirPatient.gender?.value?.getCode()}")
    val patientCI = toClassInstance(fhirPatient, "Patient")

    val probe = VersionedIdentifier().withId("Probe").withVersion("1.0.0")
    val results = engine.evaluate {
        library(probe) { expressions("GenderValue", "BirthDateValue", "FamilyNameValue") }
        parameters = mapOf("P" to patientCI)
    }.onlyResultOrThrow
    println("v5 engine evaluated P.gender.value             = ${results["GenderValue"]?.value}   (expect: 'female')")
    println("v5 engine evaluated P.birthDate.value          = ${results["BirthDateValue"]?.value}   (expect: @1985 — year precision preserved)")
    println("v5 engine evaluated First(P.name.family.value) = ${results["FamilyNameValue"]?.value}   (expect: 'Berfel')")
}
