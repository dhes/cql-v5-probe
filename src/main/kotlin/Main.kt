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

private const val FHIR = "http://hl7.org/fhir"

private val FHIR_CQL = """
    library Probe version '1.0.0'
    using FHIR version '4.0.1'
    parameter "P" FHIR.Patient
    define "GenderValue": P.gender.value
""".trimIndent()

/**
 * Increment 4 — the first REAL adapter slice: a live kotlin-fhir Patient -> engine ClassInstance.
 * Navigates the model's PUBLIC data-class property; kotlin-fhirpath's FhirModelNavigator is
 * `internal`, so it can't be reused here. Only `gender` is mapped — increment 5 generalizes this
 * into a real toCqlValue that walks all children (and confronts the reflection-free question).
 */
private fun patientToClassInstance(p: Patient): ClassInstance {
    val elements = mutableMapOf<String, Value?>()
    p.gender?.value?.getCode()?.let { code ->
        // FHIR code -> ClassInstance{ value: System.String } — the FHIR-primitive shape the engine unwraps
        elements["gender"] =
            ClassInstance(QName(FHIR, "code"), mutableMapOf<String, Value?>("value" to code.toCqlString()))
    }
    return ClassInstance(QName(FHIR, "Patient"), elements)
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
    val fhirPatient = Patient(gender = Enumeration.of(AdministrativeGender.Female, null))
    println("kotlin-fhir Patient.gender.value.getCode() = ${fhirPatient.gender?.value?.getCode()}")
    val patientCI = patientToClassInstance(fhirPatient)

    val probe = VersionedIdentifier().withId("Probe").withVersion("1.0.0")
    val result = engine.evaluate {
        library(probe) { expressions("GenderValue") }
        parameters = mapOf("P" to patientCI)
    }.onlyResultOrThrow["GenderValue"]?.value

    println("v5 engine evaluated P.gender.value = $result   (expect: 'female')  ← sourced from a kotlin-fhir object")
}
