package scalapb.spark

import com.google.protobuf.ByteString
import org.apache.spark.sql.catalyst.analysis.{GetColumnByOrdinal, UnresolvedAttribute, UnresolvedExtractValue}
import org.apache.spark.sql.catalyst.expressions
import org.apache.spark.sql.catalyst.expressions.objects.{CatalystToExternalMap, Invoke, LambdaVariable, MapObjects, NewInstance, StaticInvoke}
import org.apache.spark.sql.catalyst.expressions.{BoundReference, CreateArray, Expression, GetStructField, If, IsNull, Literal, UnaryExpression, Unevaluable}
import org.apache.spark.sql.types.{DataType, MapType, ObjectType}
import scalapb.GeneratedMessageCompanion
import scalapb.descriptors._

trait FromCatalystHelpers {
  def protoSql: ProtoSQL

  def schemaOptions: SchemaOptions = protoSql.schemaOptions

  def pmessageFromCatalyst(
      cmp: GeneratedMessageCompanion[_],
      input: Expression
  ): Expression = {
    val args =
      if (schemaOptions.isUnpackedPrimitiveWrapper(cmp.scalaDescriptor))
        cmp.scalaDescriptor.fields.map { fd =>
          fieldFromCatalyst(cmp, fd, input)
        }
      else
        cmp.scalaDescriptor.fields.map { fd =>
          fieldFromCatalyst(cmp, fd, GetStructField(input, fd.index, Some(fd.name)))
        }
    StaticInvoke(
      JavaHelpers.getClass,
      ObjectType(classOf[PValue]),
      "mkPMessage",
      Literal.fromObject(cmp) :: CreateArray(args) :: Nil
    )
  }

  def fieldFromCatalyst(
      cmp: GeneratedMessageCompanion[_],
      fd: FieldDescriptor,
      input: Expression
  ): Expression = {
    if (fd.isRepeated && !fd.isMapField) {
      val objs = MapObjects(
        (input: Expression) => singleFieldValueFromCatalyst(cmp, fd, input),
        input,
        protoSql.singularDataType(fd)
      )
      StaticInvoke(
        JavaHelpers.getClass,
        ObjectType(classOf[PValue]),
        "mkPRepeated",
        objs :: Nil
      )
    } else if (fd.isRepeated && fd.isMapField) {
      val mapEntryCmp = cmp.messageCompanionForFieldNumber(fd.number)
      val keyDesc = mapEntryCmp.scalaDescriptor.findFieldByNumber(1).get
      val valDesc = mapEntryCmp.scalaDescriptor.findFieldByNumber(2).get
      val urobjs = MyUnresolvedCatalystToExternalMap(
        input,
        (in: Expression) => singleFieldValueFromCatalyst(mapEntryCmp, keyDesc, in),
        (in: Expression) => singleFieldValueFromCatalyst(mapEntryCmp, valDesc, in),
        ProtoSQL.dataTypeFor(fd).asInstanceOf[MapType],
        classOf[Vector[(Any, Any)]]
      )
      val objs = MyCatalystToExternalMap(urobjs)
      StaticInvoke(
        JavaHelpers.getClass,
        ObjectType(classOf[PValue]),
        "mkPRepeatedMap",
        Literal.fromObject(mapEntryCmp) ::
          objs :: Nil
      )
    } else if (fd.isOptional)
      If(
        IsNull(input),
        Literal.fromObject(PEmpty, ObjectType(classOf[PValue])),
        singleFieldValueFromCatalyst(cmp, fd, input)
      )
    else singleFieldValueFromCatalyst(cmp, fd, input)
  }

  def singleFieldValueFromCatalyst(
      cmp: GeneratedMessageCompanion[_],
      fd: FieldDescriptor,
      input: Expression
  ): Expression = {
    fd.scalaType match {
      case ScalaType.ByteString =>
        val bs = StaticInvoke(
          classOf[ByteString],
          ObjectType(classOf[ByteString]),
          "copyFrom",
          input :: Nil
        )
        NewInstance(
          classOf[PByteString],
          bs :: Nil,
          ObjectType(classOf[PValue])
        )
      case ScalaType.Enum(_) =>
        val evd = cmp.enumCompanionForFieldNumber(fd.number)
        StaticInvoke(
          JavaHelpers.getClass,
          ObjectType(classOf[PValue]),
          "penumFromString",
          Literal.fromObject(evd) :: input :: Nil
        )
      case ScalaType.Message(_) =>
        pmessageFromCatalyst(
          cmp.messageCompanionForFieldNumber(fd.number),
          input
        )
      case ScalaType.String =>
        val asString = Invoke(input, "toString", ObjectType(classOf[String]))
        NewInstance(
          classOf[PString],
          asString :: Nil,
          ObjectType(classOf[PValue])
        )
      case ScalaType.Int =>
        NewInstance(classOf[PInt], input :: Nil, ObjectType(classOf[PValue]))
      case ScalaType.Long =>
        NewInstance(classOf[PLong], input :: Nil, ObjectType(classOf[PValue]))
      case ScalaType.Double =>
        NewInstance(classOf[PDouble], input :: Nil, ObjectType(classOf[PValue]))
      case ScalaType.Float =>
        NewInstance(classOf[PFloat], input :: Nil, ObjectType(classOf[PValue]))
      case ScalaType.Boolean =>
        NewInstance(
          classOf[PBoolean],
          input :: Nil,
          ObjectType(classOf[PValue])
        )
    }
  }
}

case class MyUnresolvedCatalystToExternalMap(
  child: Expression,
  @transient keyFunction: Expression => Expression,
  @transient valueFunction: Expression => Expression,
  mapType: MapType,
  collClass: Class[_]) extends UnaryExpression with Unevaluable {

  override lazy val resolved = false

  override def dataType: DataType = ObjectType(collClass)
}

object MyCatalystToExternalMap {
  def apply(u: MyUnresolvedCatalystToExternalMap): CatalystToExternalMap = {
    val mapType = u.mapType
    val keyLoopVar = LambdaVariable(
      "CatalystToExternalMap_key", mapType.keyType, nullable = false)
    val valueLoopVar = LambdaVariable(
      "CatalystToExternalMap_value", mapType.valueType, mapType.valueContainsNull)
    CatalystToExternalMap(
      keyLoopVar, u.keyFunction(keyLoopVar),
      valueLoopVar, u.valueFunction(valueLoopVar),
      u.child, u.collClass)
  }
}
