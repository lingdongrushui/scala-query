package scala.slick.driver

import scala.slick.ql._
import scala.slick.ast._
import scala.slick.SLICKException
import scala.slick.session.{PositionedParameters, PositionedResult, ResultSetType}
import java.util.UUID
import java.sql.{Blob, Clob, Date, Time, Timestamp, SQLException}
import scala.slick.util.ValueLinearizer

/**
 * SLICK driver for Microsoft Access via JdbcOdbcDriver.
 *
 * <p>This driver implements the ExtendedProfile with the following
 * limitations:</p>
 * <ul>
 *   <li>Sequences are not supported because Access does not have them.</li>
 *   <li><code>O.Default</code> is not supported because Access does not allow
 *     the definition of default values through ODBC but only via OLEDB/ADO.
 *     Trying to generate DDL SQL code which uses this feature throws an
 *     SLICKException.</li>
 *   <li>All foreign key actions are ignored. Access supports CASCADE and
 *     SET NULL but not through ODBC, only via OLEDB/ADO.</li>
 *   <li><code>Take(n)</code> modifiers are mapped to <code>SELECT TOP n</code>
 *     which may return more rows than requested if they are not unique.</li>
 *   <li><code>Drop(n)</code> modifiers are not supported. Trying to generate
 *     SQL code which uses this feature throws an SLICKException.</li>
 *   <li><code>Functions.user</code> and <code>Functions.database</code> are
 *     not available in Access. SLICK will return empty strings for
 *     both.</li>
 *   <li>Trying to use <code>java.sql.Blob</code> objects causes a NPE in the
 *     JdbcOdbcDriver. Binary data in the form of <code>Array[Byte]</code> is
 *     supported.</li>
 * </ul>
 *
 * @author szeiger
 */
class AccessDriver extends ExtendedDriver { driver =>

  override val Implicit: Implicits = new Implicits {
    override implicit def queryToQueryInvoker[T, U](q: Query[T, _ <: U]): BasicQueryInvoker[T, U] = new AccessQueryInvoker(q, driver)
  }

  val retryCount = 10
  override val typeMapperDelegates = new TypeMapperDelegates(retryCount)

  override def buildTableDDL(table: AbstractBasicTable[_]): DDL = new DDLBuilder(table).buildDDL
  override def createQueryBuilder(node: Node, vl: ValueLinearizer[_]): QueryBuilder = new QueryBuilder(node, vl)

  override def mapTypeName(tmd: TypeMapperDelegate[_]): String = tmd.sqlType match {
    case java.sql.Types.BOOLEAN => "YESNO"
    case java.sql.Types.BLOB => "LONGBINARY"
    case _ => super.mapTypeName(tmd)
  }

  class QueryBuilder(ast: Node, linearizer: ValueLinearizer[_]) extends super.QueryBuilder(ast, linearizer) {
    override protected val supportsTuples = false
    override protected val concatOperator = Some("&")

    val pi = "3.1415926535897932384626433832795"

    /*TODO
    override protected def innerBuildSelectNoRewrite(rename: Boolean) {
      query.typedModifiers[TakeDrop] match {
        case TakeDrop(_ , Some(_)) :: _ =>
          throw new SLICKException("Access does not support drop(...) modifiers")
        case TakeDrop(Some(0), _) :: _ =>
          /* Access does not allow TOP 0, so we use this workaround
           * to force the query to return no results */
          b += "SELECT * FROM ("
          super.innerBuildSelectNoRewrite(rename)
          b += ") WHERE FALSE"
        case TakeDrop(Some(n), _) :: _ =>
          /*TODO selectSlot = b.createSlot
          selectSlot += "SELECT TOP " += n += ' '
          expr(query.reified, selectSlot, rename, true)
          fromSlot = b.createSlot*/
          appendClauses()
        case _ =>
          super.innerBuildSelectNoRewrite(rename)
      }
    }
    */

    override protected def innerExpr(c: Node): Unit = c match {
      case c: Case.CaseNode => {
        b += "switch("
        var first = true
        c.clauses.foldRight(()) { (w,_) =>
          if(first) first = false
          else b += ","
          expr(w.asInstanceOf[Case.WhenNode].left)
          b += ","
          expr(w.asInstanceOf[Case.WhenNode].right)
        }
        c.elseClause match {
          case ConstColumn(null) =>
          case n =>
            if(!first) b += ","
            b += "1=1,"
            expr(n)
        }
        b += ")"
      }
      case EscFunction("degrees", ch) => b += "(180/"+pi+"*"; expr(ch); b += ')'
      case EscFunction("radians", ch) => b += "("+pi+"/180*"; expr(ch); b += ')'
      case EscFunction("ifnull", l, r) => b += "iif(isnull("; expr(l); b += "),"; expr(r); b += ','; expr(l); b += ')'
      case StdFunction("exists", q: Query[_, _]) =>
        // Access doesn't like double parens around the sub-expression
        b += "exists"; expr(q)
      case a @ ColumnOps.AsColumnOf(ch, name) => name match {
        case None if a.typeMapper eq TypeMapper.IntTypeMapper =>
          b += "cint("; expr(ch); b += ')'
        case None if a.typeMapper eq TypeMapper.LongTypeMapper =>
          b += "clng("; expr(ch); b += ')'
        case Some(n) if n.toLowerCase == "integer" =>
          b += "cint("; expr(ch); b += ')'
        case Some(n) if n.toLowerCase == "long" =>
          b += "clng("; expr(ch); b += ')'
        case _ =>
          val tn = name.getOrElse(mapTypeName(a.typeMapper(driver)))
          throw new SLICKException("Cannot represent cast to type \"" + tn + "\" in Access SQL")
      }
      case EscFunction("user") => b += "''"
      case EscFunction("database") => b += "''"
      case EscFunction("pi") => b += pi
      case _ => super.innerExpr(c)
    }

    override protected def appendOrdering(n: Node, o: Ordering) {
      if(o.nulls.last && !o.direction.desc) {
        b += "(1-isnull("
        expr(n)
        b += ")),"
      } else if(o.nulls.first && o.direction.desc) {
        b += "(1-isnull("
        expr(n)
        b += ")) desc,"
      }
      expr(n)
      if(o.direction.desc) b += " desc"
    }

    override protected def appendTakeDropClause(take: Option[Int], drop: Option[Int]) = ()
  }

  class DDLBuilder(table: AbstractBasicTable[_]) extends super.DDLBuilder(table) {
    override protected def createColumnDDLBuilder(c: RawNamedColumn) = new ColumnDDLBuilder(c)

    protected class ColumnDDLBuilder(column: RawNamedColumn) extends super.ColumnDDLBuilder(column) {
      override def appendColumn(sb: StringBuilder) {
        sb append quoteIdentifier(column.name) append ' '
        if(autoIncrement) {
          sb append "AUTOINCREMENT"
          autoIncrement = false
        }
        else sb append sqlType
        appendOptions(sb)
      }

      override protected def appendOptions(sb: StringBuilder) {
        if(notNull) sb append " NOT NULL"
        if(defaultLiteral ne null) throw new SLICKException("Default values are not supported by AccessDriver")
        if(primaryKey) sb append " PRIMARY KEY"
      }
    }

    override protected def addForeignKey(fk: ForeignKey[_ <: TableNode, _], sb: StringBuilder) {
      sb append "CONSTRAINT " append quoteIdentifier(fk.name) append " FOREIGN KEY("
      addForeignKeyColumnList(fk.linearizedSourceColumns, sb, table.tableName)
      addForeignKeyColumnList(fk.linearizedTargetColumnsForOriginalTargetTable, sb, fk.targetTable.tableName)
      sb append ")"
      // Foreign key actions are not supported by Access so we ignore them
    }
  }

  class TypeMapperDelegates(retryCount: Int) extends super.TypeMapperDelegates {
    /* Retry all parameter and result operations because ODBC can randomly throw
     * S1090 (Invalid string or buffer length) exceptions. Retrying the call can
     * sometimes work around the bug. */
    trait Retry[T] extends TypeMapperDelegate[T] {
      abstract override def nextValue(r: PositionedResult) = {
        def f(c: Int): T =
          try super.nextValue(r) catch {
            case e: SQLException if c > 0 && e.getSQLState == "S1090" => f(c-1)
          }
        f(retryCount)
      }
      abstract override def setValue(v: T, p: PositionedParameters) = {
        def f(c: Int): Unit =
          try super.setValue(v, p) catch {
            case e: SQLException if c > 0 && e.getSQLState == "S1090" => f(c-1)
          }
        f(retryCount)
      }
      abstract override def setOption(v: Option[T], p: PositionedParameters) = {
        def f(c: Int): Unit =
          try super.setOption(v, p) catch {
            case e: SQLException if c > 0 && e.getSQLState == "S1090" => f(c-1)
          }
        f(retryCount)
      }
      abstract override def updateValue(v: T, r: PositionedResult) = {
        def f(c: Int): Unit =
          try super.updateValue(v, r) catch {
            case e: SQLException if c > 0 && e.getSQLState == "S1090" => f(c-1)
          }
        f(retryCount)
      }
    }

    // This is a nightmare... but it seems to work
    class UUIDTypeMapperDelegate extends super.UUIDTypeMapperDelegate {
      override def sqlType = java.sql.Types.BLOB
      override def sqlTypeName = "LONGBINARY"
      override def setOption(v: Option[UUID], p: PositionedParameters) =
        if(v == None) p.setString(null) else p.setBytes(toBytes(v.get))
      override def nextValueOrElse(d: =>UUID, r: PositionedResult) = { val v = nextValue(r); if(v.eq(null) || r.rs.wasNull) d else v }
      override def nextOption(r: PositionedResult): Option[UUID] = { val v = nextValue(r); if(v.eq(null) || r.rs.wasNull) None else Some(v) }
    }

    /* Access does not have a TINYINT (8-bit signed type), so we use 16-bit signed. */
    class ByteTypeMapperDelegate extends super.ByteTypeMapperDelegate {
      override def sqlTypeName = "BYTE"
      override def setValue(v: Byte, p: PositionedParameters) = p.setShort(v)
      override def setOption(v: Option[Byte], p: PositionedParameters) = p.setIntOption(v.map(_.toInt))
      override def nextValue(r: PositionedResult) = r.nextInt.toByte
      override def updateValue(v: Byte, r: PositionedResult) = r.updateInt(v)
    }

    class ShortTypeMapperDelegate extends super.ShortTypeMapperDelegate {
      override def sqlTypeName = "INTEGER"
    }

    class LongTypeMapperDelegate extends super.LongTypeMapperDelegate {
      override def sqlTypeName = "LONG"
      override def setValue(v: Long, p: PositionedParameters) = p.setString(v.toString)
      override def setOption(v: Option[Long], p: PositionedParameters) = p.setStringOption(v.map(_.toString))
    }

    override val booleanTypeMapperDelegate = new BooleanTypeMapperDelegate with Retry[Boolean]
    override val blobTypeMapperDelegate = new BlobTypeMapperDelegate with Retry[Blob]
    override val bigDecimalTypeMapperDelegate = new BigDecimalTypeMapperDelegate with Retry[BigDecimal]
    override val byteTypeMapperDelegate = new ByteTypeMapperDelegate with Retry[Byte]
    override val byteArrayTypeMapperDelegate = new ByteArrayTypeMapperDelegate with Retry[Array[Byte]]
    override val clobTypeMapperDelegate = new ClobTypeMapperDelegate with Retry[Clob]
    override val dateTypeMapperDelegate = new DateTypeMapperDelegate with Retry[Date]
    override val doubleTypeMapperDelegate = new DoubleTypeMapperDelegate with Retry[Double]
    override val floatTypeMapperDelegate = new FloatTypeMapperDelegate with Retry[Float]
    override val intTypeMapperDelegate = new IntTypeMapperDelegate with Retry[Int]
    override val longTypeMapperDelegate = new LongTypeMapperDelegate with Retry[Long]
    override val shortTypeMapperDelegate = new ShortTypeMapperDelegate with Retry[Short]
    override val stringTypeMapperDelegate = new StringTypeMapperDelegate with Retry[String]
    override val timeTypeMapperDelegate = new TimeTypeMapperDelegate with Retry[Time]
    override val timestampTypeMapperDelegate = new TimestampTypeMapperDelegate with Retry[Timestamp]
    override val nullTypeMapperDelegate = new NullTypeMapperDelegate with Retry[Null]
    override val uuidTypeMapperDelegate = new UUIDTypeMapperDelegate with Retry[UUID]
  }
}

object AccessDriver extends AccessDriver

class AccessQueryInvoker[Q, R](q: Query[Q, _ <: R], profile: BasicProfile) extends BasicQueryInvoker[Q, R](q, profile) {
  /* Using Auto or ForwardOnly causes a NPE in the JdbcOdbcDriver */
  override protected val mutateType: ResultSetType = ResultSetType.ScrollInsensitive
  /* Access goes forward instead of backward after deleting the current row in a mutable result set */
  override protected val previousAfterDelete = true
}
