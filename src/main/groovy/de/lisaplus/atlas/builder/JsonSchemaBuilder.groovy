package de.lisaplus.atlas.builder

import de.lisaplus.atlas.codegen.helper.java.TypeToColor
import de.lisaplus.atlas.interf.IModelBuilder
import de.lisaplus.atlas.model.BaseType
import de.lisaplus.atlas.model.BooleanType
import de.lisaplus.atlas.model.ComplexType
import de.lisaplus.atlas.model.DummyType
import de.lisaplus.atlas.model.ExternalType
import de.lisaplus.atlas.model.InnerType
import de.lisaplus.atlas.model.IntType
import de.lisaplus.atlas.model.Model
import de.lisaplus.atlas.model.NumberType
import de.lisaplus.atlas.model.Property
import de.lisaplus.atlas.model.RefType
import de.lisaplus.atlas.model.StringType
import de.lisaplus.atlas.model.Type
import de.lisaplus.atlas.model.UnsupportedType
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import static de.lisaplus.atlas.builder.helper.BuildHelper.strFromMap
import static de.lisaplus.atlas.builder.helper.BuildHelper.string2Name
import static de.lisaplus.atlas.builder.helper.BuildHelper.makeCamelCase

/**
 * Creates meta model from JSON schema
 * Created by eiko on 01.06.17.
 */
class JsonSchemaBuilder implements IModelBuilder {
    /**
     * Container for all created types helps - makes reference handling easier
     */
    def createdTypes=[:]

    /**
     * Container for type from external schemas
     */
    Map<String,ExternalType> externalTypes = [:]

    /**
     * builds a meta model from a model files
     * @param modelFile
     * @return
     */
    Model buildModel(File modelFile) {
        def jsonSlurper = new JsonSlurper()
        def objectModel = jsonSlurper.parse(modelFile)
        if (!objectModel['$schema']) {
            def errorMsg='model file seems to be no JSON schema'
            log.error(errorMsg)
            throw new Exception(errorMsg)
        }
        String currentSchemaPath = getBasePathFromModelFile(modelFile)
        if (objectModel['definitions']) {
            // multi type schema
            return modelFromMultiTypeSchema(objectModel,currentSchemaPath)
        }
        else if (objectModel['properties']) {
            // single type schema
            return modelFromSingeTypeSchema(objectModel,modelFile.getName(),currentSchemaPath)
        }
        else {
            def errorMsg='unknown schema structure'
            log.error(errorMsg)
            throw new Exception(errorMsg)
        }
    }

    static String getBasePathFromModelFile(modelFile) {
        def path = modelFile.getPath()
        def name = modelFile.getName()
        def index = path.indexOf(name)
        return path.substring(0,index)
    }

    private Model modelFromSingeTypeSchema(def objectModel, String modelFileName,String currentSchemaPath) {
        Model model = initModel(objectModel)
        def typeName = strFromMap(objectModel,'title')
        if (!typeName) {
            int lastDot = modelFileName.lastIndexOf('.')
            if (lastDot==-1) {
                typeName = modelFileName
            }
            else {
                typeName = modelFileName.substring(0,lastDot)
            }
        }
        typeName = string2Name(typeName)
        Type newType = new Type()
        newType.name = typeName
        newType.description = strFromMap(objectModel,'description')
        newType.properties = getProperties(model,objectModel,typeName,currentSchemaPath)
        // TODO initialize extra stuff
        addNewType(newType,model)
        addExternalTypesToModel(model)
        checkModelForErrors(model)
        return model
    }

    private addExternalTypesToModel(Model model) {
        externalTypes.each { typeObj ->
            TypeToColor.setColor(typeObj.value)
            model.types.add(typeObj.value)
        }
    }

    private Model modelFromMultiTypeSchema(def objectModel,String currentSchemaPath) {
        Model model = initModel(objectModel)
        objectModel.definitions.each { typeObj ->
            def typeName = string2Name(typeObj.key)
            Type newType = new Type()
            newType.name = typeName
            newType.description = strFromMap(typeObj.value,'description')
            newType.properties = getProperties(model,typeObj.value,typeName,currentSchemaPath)
            // TODO  initialize extra stuff
            addNewType(newType,model)
        }
        addExternalTypesToModel(model)
        initRefOwnerForTypes(model)
        checkModelForErrors(model)
        return model
    }

    /**
     * fill for all types the list refOwner with object
     * @param model
     */
    private static void initRefOwnerForTypes(Model model) {
        model.types.findAll { return ! it instanceof InnerType }.each { type ->
            type.properties.findAll { it.type instanceof RefType && it.type. }.each {

                }
            }
        }
    }


    /**
     * check if there are any errors in the model definition
     * for instance unresolved Dummytypes
     * @param model
     */
    private void checkModelForErrors(def model) {
        // TODO
    }

    /**
     * wraps the append of a new type to a model, this function checks for double types
     * @param newType
     * @param model
     */
    private void addNewType(Type newType, def model) {
        def typeName = newType.name
        def alreadyCreated = createdTypes[typeName]
        if (alreadyCreated) {
            if (alreadyCreated instanceof DummyType) {
                // handle forward usage of types in declarations ... references need to be updated
                alreadyCreated.referencesToChange.each { refType ->
                    refType.type = newType
                    refType.typeName = newType.name
                }
            }
            else {
                def errorMsg = "schema contains dulplicate type: ${typeName}"
                log.error(errorMsg)
                throw new Exception(errorMsg)
            }
        }
        TypeToColor.setColor(newType)
        createdTypes[newType.name] = newType
        model.types.add(newType)
    }

    private List<Property> getProperties(Model model,def propertyParent,def parentName,String currentSchemaPath) {
        List<Property> propList = []
        propertyParent.properties.each { propObj ->
            def newProp = new Property()
            newProp.name = string2Name(propObj.key,false)
            newProp.description = propObj.value['description']
            String key = makeCamelCase(propObj.key)
            newProp.type = getPropertyType(model,propObj.value,parentName+string2Name(key),currentSchemaPath)
            propList.add(newProp)
        }
        return propList
    }

    private BaseType getPropertyType(Model model,def propObjMap,def innerTypeBaseName,String currentSchemaPath) {
        if (propObjMap.'$ref') {
            // reference to an external type
            return initRefType(propObjMap.'$ref',currentSchemaPath)
        }
        else if (! propObjMap.type) {
            def errorMsg = "property object w/o any type: ${propObjMap}"
            log.error(errorMsg)
            throw new Exception(errorMsg)
        }
        else {
            return getBaseTypeFromString(model,currentSchemaPath,propObjMap,innerTypeBaseName)
        }
    }

    private RefType initRefType(def refStr,String currentSchemaPath) {
        if (!refStr) {
            def errorMsg = "undefined refStr, so cancel init reference type"
            log.error(errorMsg)
            throw new Exception(errorMsg)
        }
        RefType refType = new RefType()
        // Examples:
        // "$ref": "#/definitions/command"
        // "$ref": "definitions.json#/address"
        // "$ref": "http: //json-schema.org/geo" - HTTP not supported (eiko)
        def localDefStrBase = '#/definitions/'
        if (refStr.startsWith(localDefStrBase)) {
            def schemaTypeName = refStr.substring(localDefStrBase.length())
            Type t = getLocalRefType(schemaTypeName)
            if (t instanceof DummyType) {
                // the needed type isn't already in the model created. later a update to the
                // right references is needed
                ((DummyType)t).referencesToChange.add(refType)
            }
            else {
                refType.type=t
                refType.typeName=t.name
            }
        }
        else {
            // "$ref": "definitions.json#/address"
            // "$ref": "http: //json-schema.org/geo" - HTTP not supported (eiko)
            Type t = getExternalRefType(refStr,currentSchemaPath)
            refType.type=t
            refType.typeName=t.name
        }
        return refType
    }
    private Type getExternalRefType(def refStr,String currentSchemaPath) {
        def alreadyLoaded = externalTypes[refStr]
        if (alreadyLoaded) {
            return alreadyLoaded
        }
        else {
            def indexOfTrenner = refStr.indexOf(EXT_REF_TRENNER)
            if (indexOfTrenner != -1) {
                // "$ref": "definitions.json#/address"
                // reference to an external multi type schema
                def fileName = refStr.substring(0,indexOfTrenner)
                Model tmpModel = loadModelFromExternalFile(fileName,refStr,currentSchemaPath)
                if ((!tmpModel) || (!tmpModel.types)) {
                    throw new Exception("loaded model doesn't contain types")
                }
                desiredName = refStr.substring(indexOfTrenner+EXT_REF_TRENNER.length())
                ExternalType extT = null
                tmpModel.types.each { type ->
                    if (type.name==desiredName) {
                        extT = new ExternalType()
                    }
                }
                if (extT==null) {
                    throw new Exception("can't fine external type ${desiredName} in model: ${fileName}")
                }
                else {
                    extT.refStr = refStr
                    extT.initFromType(type)
                    externalTypes.put(extT.name,extT)
                    return extT
                }
            }
            else {
                // "$ref": "definitions.json"
                // reference to an external single type schema
                def fileName = refStr
                Model tmpModel = loadModelFromExternalFile(fileName,refStr,currentSchemaPath)
                if ((!tmpModel) || (!tmpModel.types)) {
                    throw new Exception("loaded model doesn't contain types")
                }
                Type tmpT = tmpModel.types[0]
                ExternalType extT = new ExternalType()
                extT.refStr = refStr
                extT.initFromType(tmpT)
                externalTypes.put(refStr,extT)
                return extT
            }
        }
    }

    private Model loadModelFromExternalFile(String fileName, String refStr,String currentSchemaPath) {
        File modelFile = new File(currentSchemaPath+fileName)
        if (!modelFile.exists()) {
            throw new Exception ("can't find external reference File: ${modelFile.path}, refStr=${refStr}")
        }
        return buildModel(modelFile)
    }

    private Type getLocalRefType(def schemaTypeName) {
        // "$ref": "#/definitions/command"

        if (schemaTypeName.indexOf('/')!=-1) {
            // unsupported, something like: #/definitions/command/xxx
            def errorMsg = "unsupported local reference, types need be located under #/definitions: ${schemaTypeName}"
            log.error(errorMsg)
            throw new Exception(errorMsg)
        }
        def typeName=string2Name(schemaTypeName)
        Type alreadyCreatedType = createdTypes[typeName]
        if (alreadyCreatedType) {
            // the type is created in a earlier parsing step - fine :)
            // ... but it's possible that it is a DummyType
            return alreadyCreatedType
        }
        else {
            // the reference Points to a type that is later created - more complicated :-/
            def newDummy = new DummyType()
            createdTypes[typeName] = newDummy
            return newDummy
        }

    }

    private ComplexType initComplexType(Model model,def propertiesParent,def baseTypeName, String currentSchemaPath) {
        if (!propertiesParent) {
            def errorMsg = "undefined properties map, so cancel init complex type"
            log.error(errorMsg)
            throw new Exception(errorMsg)
        }
        ComplexType complexType = new ComplexType()
        Type newType = new InnerType()
        newType.name = baseTypeName
        newType.properties = getProperties(model,propertiesParent,baseTypeName,currentSchemaPath)
        complexType.type = newType
        addNewType(newType,model)
        return complexType
    }

    private BaseType getBaseTypeFromString(Model model,String currentSchemaPath,def propObjMap, def innerTypeBaseName, def isArrayAllowed=true) {
        switch (propObjMap.type) {
            case 'string':
                return new StringType()
            case 'integer':
                return new IntType()
            case 'number':
                return new NumberType()
            case 'boolean':
                return new BooleanType()
            case 'object':
                if (propObjMap.patternProperties) {
                    log.warn("unsupported 'patternProperties' entry found")
                    return new UnsupportedType()
                }
                else
                    return initComplexType(model,propObjMap,innerTypeBaseName,currentSchemaPath)
            case 'array':
                if (!isArrayAllowed) {
                    def errorMsg = "detect not allowed sub array type"
                    log.error(errorMsg)
                    throw new Exception(errorMsg)
                }
                if (propObjMap.items.type) {
                    BaseType ret = getBaseTypeFromString(model,currentSchemaPath,propObjMap.items,innerTypeBaseName+'Item',false)
                    ret.isArray = true
                    return ret
                }
                else if (propObjMap.items['$ref']) {
                    BaseType ret = initRefType(propObjMap.items['$ref'],currentSchemaPath)
                    ret.isArray = true
                    return ret
                }
                else {
                    def errorMsg = "unknown array type"
                    log.error(errorMsg)
                    throw new Exception(errorMsg)
                }
            default:
                def errorMsg = "property with unknown type: ${propObjMap.type}"
                log.error(errorMsg)
                throw new Exception(errorMsg)
        }
    }

    private Model initModel(def objectModel) {
        Model model = new Model()
        model.title = strFromMap(objectModel, 'title')
        model.description = strFromMap(objectModel, 'description')

        if (objectModel.properties && objectModel.properties.model_version && objectModel.properties.model_version.enum) {
            objectModel.properties.model_version.enum.each {
                if (!model.version) {
                    model.version = it
                }
            }
        }
        else if (objectModel.version) {
            model.version = objectModel.version
        }
        return model
    }

    private static final String EXT_REF_TRENNER='#/'
    private static final Logger log=LoggerFactory.getLogger(JsonSchemaBuilder.class);
}
