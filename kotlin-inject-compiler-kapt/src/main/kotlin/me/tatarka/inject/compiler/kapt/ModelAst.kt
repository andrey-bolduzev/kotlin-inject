package me.tatarka.inject.compiler.kapt

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ClassName
import kotlinx.metadata.*
import kotlinx.metadata.jvm.JvmMethodSignature
import kotlinx.metadata.jvm.annotations
import kotlinx.metadata.jvm.getterSignature
import kotlinx.metadata.jvm.signature
import me.tatarka.inject.compiler.*
import java.util.*
import javax.annotation.processing.Messager
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.*
import javax.lang.model.type.*
import javax.lang.model.util.ElementFilter
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic
import kotlin.reflect.KClass

interface ModelAstProvider :
    AstProvider {

    val env: ProcessingEnvironment

    val types: Types get() = env.typeUtils
    val elements: Elements get() = env.elementUtils
    val messager: Messager get() = env.messager

    override val messenger: Messenger
        get() = ModelAstMessenger(messager)

    fun TypeElement.toAstClass(): AstClass {
        return ModelAstClass(this@ModelAstProvider, this, metadata?.toKmClass())
    }

    override fun findFunctions(packageName: String, functionName: String): List<AstFunction> {
        val packageElement = elements.getPackageElement(packageName)
        val results = mutableListOf<AstFunction>()
        for (element in ElementFilter.typesIn(packageElement.enclosedElements)) {
            for (function in ElementFilter.methodsIn(element.enclosedElements)) {
                if (function.simpleName.contentEquals(functionName)
                    && function.modifiers.contains(Modifier.STATIC) && function.modifiers.contains(Modifier.STATIC) && function.modifiers.contains(
                        Modifier.FINAL
                    )
                ) {
                    val metadata = element.metadata?.toKmPackage() ?: continue
                    val kmFunction = metadata.functions.find { it.name == functionName } ?: continue
                    results.add(ModelAstFunction(this, element.toAstClass() as ModelAstClass, function, kmFunction))
                }
            }
        }
        return results
    }

    override fun declaredTypeOf(klass: KClass<*>, vararg astTypes: AstType): AstType {
        val type = elements.getTypeElement(klass.java.canonicalName)
        return ModelAstType(
            this,
            elementDeclaredType(type, astTypes.asList()),
            kmDeclaredType(klass.kmClassName(), astTypes.asList())
        )
    }

    private fun kmDeclaredType(name: kotlinx.metadata.ClassName, astTypes: List<AstType>): KmType = KmType(0).apply {
        classifier = KmClassifier.Class(name).apply {
            arguments.addAll(astTypes.map { type ->
                require(type is ModelAstType)
                KmTypeProjection(KmVariance.INVARIANT, type.kmType)
            })
        }
    }

    private fun KClass<*>.kmClassName(): kotlinx.metadata.ClassName {
        val qualifiedName: String
        val simpleName: String
        if (this.qualifiedName != null) {
            qualifiedName = this.qualifiedName!!
            simpleName = this.simpleName!!
        } else {
            qualifiedName = java.canonicalName
            simpleName = java.simpleName
        }
        val packageName = qualifiedName.removeSuffix(".$simpleName").replace('.', '/')
        return "${packageName}/${simpleName}"
    }

    private fun ModelAstType.kmClassName(): kotlinx.metadata.ClassName {
        return when (val classifier = kmType?.classifier) {
            is KmClassifier.Class -> classifier.name
            is KmClassifier.TypeAlias -> classifier.name
            is KmClassifier.TypeParameter -> ""
            null -> element.toString()
        }
    }

    private fun elementDeclaredType(type: TypeElement, astTypes: List<AstType>) =
        types.getDeclaredType(type, *astTypes.map {
            (it as ModelAstType).type
        }.toTypedArray())

    override fun AstElement.toTrace(): String {
        require(this is ModelAstElement)
        return when (this) {
            is ModelAstMethod -> "${parent.type}.${toString()}"
            else -> toString()
        }
    }

    override fun TypeSpec.Builder.addOriginatingElement(astClass: AstClass): TypeSpec.Builder = apply {
        require(astClass is ModelAstClass)
        addOriginatingElement(astClass.element)
    }
}

class ModelAstMessenger(private val messager: Messager) : Messenger {
    override fun warn(message: String, element: AstElement?) {
        print(Diagnostic.Kind.WARNING, message, element)
    }

    override fun error(message: String, element: AstElement?) {
        print(Diagnostic.Kind.ERROR, message, element)
    }

    private fun print(kind: Diagnostic.Kind, message: String, element: AstElement?) {
        messager.printMessage(kind, message, (element as? ModelAstElement)?.element)
    }
}

private interface ModelAstElement : ModelAstProvider, AstAnnotated {
    val element: Element

    override fun hasAnnotation(className: String): Boolean {
        return element.hasAnnotation(className)
    }

    override fun annotationAnnotatedWith(className: String): AstAnnotation? {
        return element.annotationAnnotatedWith(className)?.let {
            ModelAstAnnotation(this, it, null)
        }
    }
}

private interface ModelAstMethod : ModelAstElement {
    val name: String
    val parent: AstClass
    override val element: ExecutableElement
}

private class ModelBasicElement(provider: ModelAstProvider, override val element: Element) : AstBasicElement(),
    ModelAstElement, ModelAstProvider by provider {
    override val simpleName: String get() = element.simpleName.toString()
}

private class PrimitiveModelAstClass(
    override val type: ModelAstType
) : AstClass(), ModelAstProvider by type {
    override val packageName: String = "kotlin"
    override val name: String = type.toString()
    override val isAbstract: Boolean = false
    override val isPrivate: Boolean = false
    override val isInterface: Boolean = false
    override val companion: AstClass? = null
    override val superTypes: List<AstClass> = emptyList()
    override val primaryConstructor: AstConstructor? = null
    override val methods: List<AstMethod> = emptyList()

    override fun asClassName(): ClassName = throw UnsupportedOperationException()

    override fun equals(other: Any?): Boolean {
        return other is PrimitiveModelAstClass && type == other.type
    }

    override fun hashCode(): Int {
        return type.hashCode()
    }

    override fun hasAnnotation(className: String): Boolean = false
    override fun annotationAnnotatedWith(className: String): AstAnnotation? = null
}

private class ModelAstClass(
    provider: ModelAstProvider,
    override val element: TypeElement,
    val kmClass: KmClass?
) : AstClass(),
    ModelAstElement, ModelAstProvider by provider {

    override val packageName: String
        get() {
            if (kmClass != null) {
                return kmClass.packageName
            }
            return elements.getPackageOf(element).qualifiedName.toString()
        }

    override val name: String get() = element.simpleName.toString()

    override val isAbstract: Boolean
        get() = kmClass?.isAbstract() ?: false

    override val isPrivate: Boolean
        get() = kmClass?.isPrivate() ?: false

    override val isInterface: Boolean
        get() = kmClass?.isInterface() ?: false

    override val companion: AstClass?
        get() {
            val companionName = kmClass?.companionObject ?: return null
            val companionType = ElementFilter.typesIn(element.enclosedElements).firstOrNull { type ->
                type.simpleName.contentEquals(companionName)
            }
            return companionType?.toAstClass()
        }

    override val superTypes: List<AstClass>
        get() {
            return mutableListOf<AstClass>().apply {
                val superclassType = element.superclass
                if (superclassType !is NoType) {
                    val superclass = types.asElement(superclassType) as TypeElement
                    add(superclass.toAstClass())
                }
                addAll(element.interfaces.mapNotNull { ifaceType ->
                    val iface = types.asElement(ifaceType) as TypeElement
                    iface.toAstClass()
                })
            }
        }

    override val primaryConstructor: AstConstructor?
        get() {
            return ElementFilter.constructorsIn(element.enclosedElements).mapNotNull { constructor ->
                //TODO: not sure how to match constructors
                ModelAstConstructor(
                    this,
                    this,
                    constructor,
                    kmClass?.constructors?.first()
                )
            }.firstOrNull()
        }

    override val methods: List<AstMethod>
        get() {
            val kmClass = kmClass ?: return emptyList()
            val methods = mutableMapOf<String, ExecutableElement>()

            for (method in ElementFilter.methodsIn(element.enclosedElements)) {
                methods[method.simpleSig] = method
            }

            val result = mutableListOf<AstMethod>()
            for (property in kmClass.properties) {
                val method = methods[property.getterSignature?.simpleSig] ?: continue
                result.add(
                    ModelAstProperty(
                        this,
                        this,
                        method,
                        property
                    )
                )
            }
            for (function in kmClass.functions) {
                val method = methods[function.signature?.simpleSig] ?: continue
                result.add(
                    ModelAstFunction(
                        this,
                        this,
                        method,
                        function
                    )
                )
            }

            return result
        }

    override val type: AstType
        get() {
            return ModelAstType(this, element.asType(), kmClass?.type)
        }

    override fun asClassName(): ClassName = element.asClassName()

    override fun equals(other: Any?): Boolean = other is ModelAstElement && element == other.element

    override fun hashCode(): Int = element.hashCode()
}

private class ModelAstConstructor(
    provider: ModelAstProvider,
    private val parent: ModelAstClass,
    override val element: ExecutableElement,
    private val kmConstructor: KmConstructor?
) : AstConstructor(parent),
    ModelAstElement, ModelAstProvider by provider {

    override val parameters: List<AstParam>
        get() {
            val params = element.parameters
            val kmParams: List<KmValueParameter> = kmConstructor?.valueParameters ?: emptyList()
            return params.mapIndexed { index, param ->
                val kmParam = kmParams.getOrNull(index)
                ModelAstParam(
                    this,
                    param,
                    parent.kmClass,
                    kmParam
                )
            }
        }

    override fun equals(other: Any?): Boolean {
        return other is ModelAstConstructor && element == other.element
    }

    override fun hashCode(): Int {
        return element.hashCode()
    }
}

private class ModelAstFunction(
    provider: ModelAstProvider,
    override val parent: AstClass,
    override val element: ExecutableElement,
    private val kmFunction: KmFunction
) : AstFunction(),
    ModelAstMethod, ModelAstProvider by provider {

    override val name: String get() = kmFunction.name

    override val isAbstract: Boolean
        get() = kmFunction.isAbstract()

    override val isPrivate: Boolean
        get() = kmFunction.isPrivate()

    override val isSuspend: Boolean
        get() = kmFunction.isSuspend()

    override val returnType: AstType
        get() = ModelAstType(
            this,
            element.returnType,
            kmFunction.returnType
        )

    override fun returnTypeFor(enclosingClass: AstClass): AstType {
        require(enclosingClass is ModelAstClass)
        val declaredType = enclosingClass.element.asType() as DeclaredType
        val methodType = types.asMemberOf(declaredType, element) as ExecutableType
        return ModelAstType(
            this,
            methodType.returnType,
            kmFunction.returnType
        )
    }

    override val receiverParameterType: AstType?
        get() = kmFunction.receiverParameterType?.let {
            ModelAstType(this, element.parameters[0].asType(), it)
        }

    override val parameters: List<AstParam>
        get() {
            val params = when {
                kmFunction.receiverParameterType != null -> {
                    // drop the extension function receiver if present
                    element.parameters.drop(1)
                }
                kmFunction.isSuspend() -> {
                    // drop last continuation parameter
                    element.parameters.dropLast(1)
                }
                else -> {
                    element.parameters
                }
            }
            val kmParams: List<KmValueParameter> = kmFunction.valueParameters
            return params.mapIndexed { index, param ->
                val kmParam = kmParams.getOrNull(index)
                ModelAstParam(
                    this,
                    param,
                    null,
                    kmParam
                )
            }
        }

    override fun overrides(other: AstMethod): Boolean {
        require(other is ModelAstMethod)
        return elements.overrides(element, other.element, parent.element)
    }

    override fun asMemberName(): MemberName {
        return MemberName(elements.getPackageOf(element).qualifiedName.toString(), name)
    }

    override fun equals(other: Any?): Boolean {
        return other is ModelAstFunction && element == other.element
    }

    override fun hashCode(): Int {
        return element.hashCode()
    }
}

private class ModelAstProperty(
    provider: ModelAstProvider,
    override val parent: AstClass,
    override val element: ExecutableElement,
    private val kmProperty: KmProperty
) : AstProperty(),
    ModelAstMethod, ModelAstProvider by provider {

    override val name: String get() = kmProperty.name

    override val isAbstract: Boolean
        get() = kmProperty.isAbstract()

    override val isPrivate: Boolean
        get() = kmProperty.isPrivate()

    override val returnType: AstType
        get() = ModelAstType(
            this,
            element.returnType,
            kmProperty.returnType
        )

    override fun returnTypeFor(enclosingClass: AstClass): AstType {
        require(enclosingClass is ModelAstClass)
        val declaredType = enclosingClass.element.asType() as DeclaredType
        val methodType = types.asMemberOf(declaredType, element) as ExecutableType
        return ModelAstType(
            this,
            methodType.returnType,
            kmProperty.returnType
        )
    }

    override val receiverParameterType: AstType?
        get() = kmProperty.receiverParameterType?.let {
            ModelAstType(this, element.parameters[0].asType(), it)
        }

    override fun overrides(other: AstMethod): Boolean {
        require(other is ModelAstMethod)
        return elements.overrides(element, other.element, parent.element)
    }

    override fun asMemberName(): MemberName {
        return MemberName(elements.getPackageOf(element).qualifiedName.toString(), name)
    }

    override fun equals(other: Any?): Boolean {
        return other is ModelAstProperty && element == other.elements
    }

    override fun hashCode(): Int {
        return element.hashCode()
    }
}

private class ModelAstType(
    provider: ModelAstProvider,
    val type: TypeMirror,
    val kmType: KmType?
) : AstType(),
    ModelAstElement, ModelAstProvider by provider {

    override val element: Element get() = types.asElement(type)

    override val packageName: String
        get() {
            if (kmType != null) {
                return kmType.packageName
            }
            return elements.getPackageOf(element).qualifiedName.toString()
        }

    override val simpleName: String
        get() {
            if (kmType != null) {
                return kmType.simpleName
            }
            return element.simpleName.toString()
        }

    override val annotations: List<AstAnnotation>
        get() {
            val typeAnnotations = type.annotationMirrors
            val kmTypeAnnotations = kmType?.annotations
            return typeAnnotations.mapIndexed { index, annotation ->
                val kmAnnotation = kmTypeAnnotations?.get(index)
                ModelAstAnnotation(
                    this,
                    annotation,
                    kmAnnotation
                )
            }
        }

    override val arguments: List<AstType>
        get() {
            val kmArgs: List<KmType?> = kmType?.arguments?.map { it.type } ?: emptyList()
            val args: List<TypeMirror> = (type as DeclaredType).typeArguments
            return if (args.size == kmArgs.size) {
                args.zip(kmArgs) { a1, a2 -> ModelAstType(this, a1, a2) }
            } else {
                args.map { ModelAstType(this, it, null) }
            }
        }

    override fun isUnit(): Boolean = type is NoType

    override fun isFunction(): Boolean {
        return kmType?.isFunction() == true
    }

    override fun isTypeAlis(): Boolean {
        return kmType?.abbreviatedType != null
    }

    override fun resolvedType(): AstType {
        val abbreviatedType = kmType?.abbreviatedType
        return if (abbreviatedType != null) {
            ModelAstType(this, type, abbreviatedType)
        } else {
            this
        }
    }

    override fun asElement(): AstBasicElement =
        ModelBasicElement(this, element)

    override fun toAstClass(): AstClass {
        return when (type) {
            is PrimitiveType -> PrimitiveModelAstClass(this)
            is ArrayType -> PrimitiveModelAstClass(this)
            is DeclaredType, is TypeVariable -> (types.asElement(type) as TypeElement).toAstClass()
            else -> throw IllegalStateException("unknown type: $type")
        }
    }

    override fun asTypeName(): TypeName {
        return type.asTypeName(kmType)
    }

    override fun isAssignableFrom(other: AstType): Boolean {
        require(other is ModelAstType)
        return types.isAssignable(other.type, type)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is ModelAstType) return false
        return if (kmType != null && other.kmType != null) {
            kmType.eqv(other.kmType)
        } else {
            types.isSameType(type, other.type)
        }
    }

    override fun hashCode(): Int {
        return kmType?.eqvHashCode() ?: type.eqvHashCode()
    }
}

private class ModelAstAnnotation(
    provider: ModelAstProvider,
    val mirror: AnnotationMirror,
    private val kmAnnotation: KmAnnotation?
) : AstAnnotation(),
    ModelAstElement, ModelAstProvider by provider {
    override val element: Element get() = types.asElement(mirror.annotationType)

    override val type: AstType
        get() = ModelAstType(this, mirror.annotationType, kmAnnotation?.type)

    override fun equals(other: Any?): Boolean {
        if (other !is ModelAstAnnotation) return false
        return if (kmAnnotation != null && other.kmAnnotation != null) {
            kmAnnotation == other.kmAnnotation
        } else {
            mirror.eqv(other.mirror)
        }
    }

    override fun hashCode(): Int {
        return kmAnnotation?.hashCode() ?: mirror.eqvHashCode()
    }

    override fun toString(): String {
        return "@${mirror.annotationType}(${
            if (kmAnnotation != null) {
                kmAnnotation.arguments.toList()
                    .joinToString(separator = ", ") { (name, value) -> "$name=${value.value}" }
            } else {
                mirror.elementValues.toList()
                    .joinToString(separator = ", ") { (element, value) -> "${element.simpleName}=${value.value}" }
            }
        })"
    }
}

private class ModelAstParam(
    provider: ModelAstProvider,
    override val element: VariableElement,
    val kmParent: KmClass?,
    val kmValueParameter: KmValueParameter?
) : AstParam(),
    ModelAstElement, ModelAstProvider by provider {

    override val name: String
        get() {
            return kmValueParameter?.name ?: element.simpleName.toString()
        }

    override val type: AstType
        get() = ModelAstType(this, element.asType(), kmValueParameter?.type)

    override val isVal: Boolean get() {
        val param = kmValueParameter ?: return false
        val parent = kmParent ?: return false
        return parent.properties.any { it.name == param.name }
    }

    override val isPrivate: Boolean get() {
        val param = kmValueParameter ?: return false
        val parent = kmParent ?: return false
        return parent.properties.find { it.name == param.name }?.isPrivate() ?: false
    }

    override val hasDefault: Boolean
        get() = kmValueParameter?.hasDefault() ?: false

    override fun asParameterSpec(): ParameterSpec {
        return ParameterSpec(name, type.asTypeName())
    }
}

private val KmClass.type: KmType
    get() = KmType(0).apply {
        classifier = KmClassifier.Class(name)
    }

private val KmAnnotation.type: KmType
    get() = KmType(0).apply {
        classifier = KmClassifier.Class(className)
    }

private fun KClass<*>.toKmType(args: List<KmTypeProjection>): KmType =
    (qualifiedName ?: java.canonicalName).toKmType(args)

private fun String.toKmType(args: List<KmTypeProjection>): KmType = KmType(0).apply {
    classifier = KmClassifier.Class(this@toKmType).apply {
        arguments.addAll(args)
    }
}

val AstClass.element: TypeElement
    get() {
        require(this is ModelAstClass)
        return element
    }
