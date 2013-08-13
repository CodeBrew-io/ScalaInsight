package com.github.jedesah.codesheet.api

import scala.reflect.runtime.{currentMirror => cm}
import scala.reflect.runtime.universe._
import scala.tools.reflect.ToolBox
import scala.tools.reflect.ToolBoxError
import scala.reflect.runtime.universe.Flag._

import com.github.jedesah.Math
import com.github.jedesah.AugmentedCollections._

object ScalaCodeSheet {

    case class BlockResult(children: List[Result]) {
        override def toString = {
            val outputList = children.foldLeft(List.empty[String]) { (list, result) =>
                val alreadyThere = list.lift(result.line)
                val prepend:String = alreadyThere.map( _ + "; ").getOrElse("")
                list.safeUpdate(result.line, prepend + result.toString)
            }
            outputList.mkString("\n")
        }
    }
    abstract class Result(val line: Int)
    case class ValDefResult(name: String, inferredType: Option[String], inner: BlockResult, override val line: Int) extends Result(line) {
        override def toString = name + inferredType.map(": " + _).getOrElse("") + " = " + inner.toString
    }
    case class DefDefResult(name: String, params: List[AssignOrNamedArg], inferredType: Option[String], inner: BlockResult, override val line: Int) extends Result(line) {
        override def toString = name + (if (params.length == 0) "" else "(" + params.mkString(", ") + ")") + inferredType.map(": " + _).getOrElse("") + " => " + inner.toString
    }
    case class ExpressionResult(steps: List[Tree], finalResult: SimpleResult, override val line: Int) extends Result(line) {
        override def toString = steps.mkString(" => ") + finalResult
    }
    abstract class SimpleResult(line: Int) extends Result(line)
    case class ExceptionResult(ex: Exception, override val line: Int) extends SimpleResult(line) {
        override def toString = "throws " + ex
    }
    case class NotImplementedResult(override val line: Int) extends SimpleResult(line) {
        override def toString = "???"
    }
    case class ObjectResult(value: Any, override val line: Int) extends SimpleResult(line) {
        override def toString = value.toString
    }
    implicit def createObjectResult(value: Any) = ObjectResult(value, 0)

    val notImplSymbol = Ident(newTermName("$qmark$qmark$qmark"))

    def evaluate(AST: Tree, outputResult: List[String], toolBox: ToolBox[reflect.runtime.universe.type], symbols: List[Tree] = Nil): List[String] = {
      lazy val classDefs: Traversable[ClassDef] = symbols.collect{ case elem: ClassDef => elem}

      def updateOutput(oldOutput: List[String] = outputResult, newOutput: String, line: Int = AST.pos.line) = {
          // -1 because we are dealing with a zero based collection but 1 based lines in the string
          val index = line - 1
          val previousOutputOnLine = oldOutput(index)
          // We could conceivably have more than one result on a single line
          val updatedOutput = if (previousOutputOnLine == "") newOutput else previousOutputOnLine + " ; " + newOutput
          oldOutput.updated(index, updatedOutput)
      }

      def evaluateWithSymbols(expr: Tree, extraSymbols: Traversable[Tree] = Set()) = {
          // In Scala 2.10.1, when you create a Block using the following deprecated method, if you pass in only one argument
          // it will be a block that adds a unit expressions at it's end and evaluates to unit. Useless behavior as far as we are concerned.
          // TODO: Remove use of deprecated Block constructor.
          val totalSymbols = symbols ++ extraSymbols
          println(Block(totalSymbols.toList :+ expr: _*))
          if (totalSymbols.isEmpty) toolBox.eval(expr)
          else toolBox.eval(Block(totalSymbols.toList :+ expr: _*))
      }

      def evaluateTypeDefBody(name: String, body: List[Tree], classParams: List[ValDef] = Nil): List[String] = {
            val newOutput = body.foldLeft(outputResult) { (result, child) =>
                // One (such as myself) might think it would be a good idea to remove the current
                // member from the body (list of members) and only supply all other members
                // but it turns out that because of potential circular dependencies and recursive function
                // definitions that it is better to just supply them all including the member we are currently
                // evaluating.
                // We only grab the rhs of the current member when evaluating so, it's not like
                // the compiler is going to complain about a duplicate definition.
                val allSymbols: List[Tree] = ((symbols ::: classParams) :+ AST) ::: body
                evaluate(child, result, toolBox, allSymbols)
            }
            // If nothing in the body is worthy of printing (or the body is empty), don't bother
            // printing surrounding stuff wither
            if (newOutput == outputResult) outputResult
            else {
                val signature:String = name + paramList(classParams):String
                val output = s"$signature {"
                val withBefore = updateOutput(newOutput, output)
                val lastLine = body.map(_.pos.line).max + 1
                updateOutput(withBefore, "}", lastLine)
            }
      }

      AST match {
        // There is what appears to be a bug in Scala 2.10.1 where you cannot use a capital letter in a case statement with a @
        case ast @ ValDef(_, newTermName, _, assignee) => {
          if (isSimpleExpression(assignee) || assignee.equalsStructure(notImplSymbol)) outputResult
          else {
              val evaluatedAssignement = evaluateWithSymbols(assignee)
              val output = s"$newTermName = $evaluatedAssignement"
              updateOutput(newOutput = output)
          }
        }
        // We fold over each child and all of it's preceding childs (inits) and evaluate
        // it with it's preceding children
        case expr: Block => expr.children.inits.toList.reverse.drop(1).foldLeft(outputResult) { (result, childs) =>
            evaluate(childs.last, result, toolBox, symbols ++ childs.init)
        }
        case classDef: ClassDef =>
            // TODO: Handle abstract classes in a more awesome way than ignoring them
            if (classDef.isAbstract) outputResult
            else
                classDef.constructorOption.flatMap { constructor =>
                    constructor.sampleParamsOption(classDefs).map { sampleValues =>
                        // We remove the value defintions resulting from the class parameters and the primary constructor
                        val body = classDef.impl.body.filter {
                            case valDef: ValDef => !sampleValues.exists( sampleValDef => sampleValDef.name == valDef.name)
                            case other => other != constructor
                        }
                        evaluateTypeDefBody(classDef.name.toString, body, sampleValues)
                    }
                } getOrElse {
                    outputResult
                }
        case moduleDef: ModuleDef => {
            val body = moduleDef.impl.body.filter(!isConstructor(_))
            evaluateTypeDefBody(moduleDef.name.toString, body)
        }
        case defdef : DefDef => {
            val isNotImplemented = defdef.rhs.equalsStructure(notImplSymbol)
            if (isSimpleExpression(defdef.rhs) || (defdef.vparamss.isEmpty && isNotImplemented))
                outputResult
            else {
                defdef.sampleParamsOption(classDefs) map { sampleValues =>
                    val rhs = if (isNotImplemented) "???" else evaluateWithSymbols(defdef.rhs, sampleValues).toString
                    val signature:String = defdef.name + paramList(sampleValues):String
                    val output = s"$signature => $rhs"
                    val newTotalOutput = updateOutput(newOutput = output)
                    // If the rhs is a block (contains other interesting value defitions to evalute),
                    // then delve down recursively, or else, just return the function header output
                    defdef.rhs match {
                        case rhs: Block => evaluate(rhs, newTotalOutput, toolBox, symbols ::: sampleValues)
                        case _ => newTotalOutput
                    }
                } getOrElse {
                    outputResult
                }
            }
        }
        case EmptyTree => outputResult
        case _ => {
          val result = evaluateWithSymbols(AST)
          val output = result match {
            case _ : scala.runtime.BoxedUnit => ""
            case _ => result.toString
          }
          updateOutput(newOutput = output)
        }
      }
    }

    def prettyPrint(tree: Tree): String = {
        // I believe this is not necessary anymore
        /*case tree: Apply => tree.fun match {
            case fun: Select => fun.qualifier.toString + "(" + tree.args.mkString(", ") + ")"
            case fun: Ident => fun.toString + "(" + tree.args.mkString(", ") + ")"
        }*/
        deconstructAnonymous(tree).map { case(name, body) =>
            val bodyString = if (body.isEmpty) "{}" else s"""{ ${body.mkString("; ")} }"""
            s"new $name $bodyString"
        }.getOrElse(tree.toString)
    }

    def isSimpleExpression(tree: Tree): Boolean = tree match {
        case _ : Literal => true
        case tree: Apply => {
            lazy val composedOfSimple = tree.args.forall(isSimpleExpression(_))
            tree.fun match {
                case fun: Select => fun.children.lift(0).map { case _ : New => composedOfSimple case _ => false }.getOrElse(false)
                case fun: Ident => composedOfSimple
            }
        }
        case _ => false
    }

    def computeResults(code: String): List[String] = try {
        val toolBox = cm.mkToolBox()
        val AST = toolBox.parse(code)
        val outputResult = (0 until code.lines.size).map( _ => "").toList
        evaluate(AST, outputResult, toolBox)
    } catch {
      case ToolBoxError(msg, cause) => {
        val userMessage = msg.dropWhile(_ != ':').drop(2).dropWhile(char => char == ' ' || char == '\n' || char == '\t')
        userMessage :: (if (code.lines.isEmpty) Nil else computeResults(code.lines.drop(1).mkString))
      }
    }

    implicit class DefDefWithSamples(defdef: DefDef) {
        def sampleParamsOption(classDefs: Traversable[ClassDef] = Nil,
                               samplePool: SamplePool = createDefaultSamplePool): Option[List[ValDef]] = {
            def sequentialEagerTerminationImpl(valDefs: List[ValDef]): Option[List[ValDef]] = {
                if (valDefs.isEmpty) Some(Nil)
                else {
                    valDefs.head.withSampleValue(classDefs, samplePool).map { newValDef =>
                        sequentialEagerTerminationImpl(valDefs.tail).map { rest =>
                            newValDef :: rest
                        }
                    }.flatten
                }
            }
            // TODO: Handle currying better
            val allParams = defdef.vparamss.flatten
            sequentialEagerTerminationImpl(allParams)
        }
    }

    implicit class ValDefWithSample(valDef: ValDef) {

        def withSampleValue(classDefs: Traversable[ClassDef] = Nil,
                            samplePool: SamplePool = createDefaultSamplePool): Option[ValDef] =
            if (valDef.rhs.isEmpty)
                valDef.sampleValue(classDefs, samplePool).map { rhs =>
                    ValDef(Modifiers(), valDef.name, TypeTree(), rhs)
                }
            else
                Some(valDef)

        def sampleValue(classDefs: Traversable[ClassDef] = Nil,
                        samplePool: SamplePool = createDefaultSamplePool): Option[Tree] = {
            valDef.tpt.sampleValue(classDefs, samplePool)
        }
    }

    implicit class TreeWithSample(tree: Tree) {

        def sampleValue(classDefs: Traversable[ClassDef] = Nil,
                        samplePool: SamplePool = createDefaultSamplePool): Option[Tree] = {

            def assignValueOfCustomType: Option[Tree] = {
                val concretePred = (classDef: ClassDef) => {
                    val isSameConcreteClass = classDef.name.toString == tree.toString && !classDef.isAbstract
                    val isSubClass = classDef.impl.parents.exists( (parent: Tree) => { parent.toString == tree.toString})
                    isSameConcreteClass || isSubClass
                }
                val abstractPred = (classDef: ClassDef) => {
                    classDef.name.toString == tree.toString && classDef.isAbstract
                }
                // TODO: Consider possibility that an object could extend an abstract type and be of use here
                classDefs.find(concretePred).orElse(classDefs.find(abstractPred)).flatMap { classDef =>
                    def assignAnonClass: Option[Tree] = {
                        val implementedMembersOption: List[Option[ValOrDefDef]] = classDef.abstractMembers.map {
                            case valDef: ValDef => valDef.withSampleValue(classDefs, samplePool)
                            case defDef: DefDef => defDef.tpt.sampleValue(classDefs, samplePool).map { sampleImpl =>
                                DefDef(Modifiers(), defDef.name, List(), defDef.vparamss, TypeTree(), sampleImpl)
                            }
                        }
                        if (implementedMembersOption.exists(_.isEmpty)) None
                        else {
                            val implementedMembers = implementedMembersOption.map(_.get)
                            Some(anonClass(classDef.name.toString, implementedMembers))
                        }
                    }
                    if (classDef.isTrait) assignAnonClass
                    else classDef.constructorOption.flatMap { constructorDef =>
                        constructorDef.sampleParamsOption(classDefs, samplePool).flatMap { innerValues =>
                            if (classDef.isAbstract) {
                                // TODO: Handle case where abstract class takes parameters
                                assignAnonClass
                            }
                            else if (classDef.isCaseClass)
                                Some(Apply(Ident(newTermName(classDef.name.toString)), innerValues.map(_.rhs)))
                            else
                                Some(Apply(Select(New(Ident(newTypeName(classDef.name.toString))), nme.CONSTRUCTOR), innerValues.map(_.rhs)))
                        }
                    }
                }
            }
            tree match {
                case tpt: AppliedTypeTree => {
                    val innerType = tpt.args(0)
                    tpt.tpt.toString match {
                        case "List" => {
                            val length = samplePool.nextSeqLength
                            if (length == 0)
                                Some(Ident(newTermName("Nil")))
                            else {
                                val innerSamplesOpt = (0 until length).map{ useless => innerType.sampleValue(classDefs, samplePool)}
                                if (innerSamplesOpt.exists(_.isEmpty)) None
                                else {
                                    val innerSamples = innerSamplesOpt.map(_.get)
                                    val tree = Apply(Ident(newTermName("List")), innerSamples.toList)
                                    Some(tree)
                                }
                            }
                        }
                        case "Option" => {
                            val isSome = samplePool.nextOptionIsSome
                            if (isSome)
                                innerType.sampleValue(classDefs, samplePool).map { innerValue =>
                                    Apply(Ident(newTermName("Some")), List(innerValue))
                                }
                            else
                                Some(Ident(newTermName("None")))
                        }
                        case "Seq" => {
                            val length = samplePool.nextSeqLength
                            if (length == 0)
                                Some(Ident(newTermName("Nil")))
                            else {
                                val innerSamplesOpt = (0 until length).map(useless => innerType.sampleValue(classDefs, samplePool))
                                if (innerSamplesOpt.exists(_.isEmpty)) None
                                else {
                                    val innerSamples = innerSamplesOpt.map(_.get)
                                    val tree = Apply(Ident(newTermName("Seq")), innerSamples.toList)
                                    Some(tree)
                                }
                            }
                        }
                        case _ => None
                    }
                }
                case tpt: ExistentialTypeTree => {
                    // TODO: Remove hard coding for only one type param
                    // Probably fixed by removing the 0 index retrieval and the list construction
                    val upperBound = tpt.whereClauses(0).asInstanceOf[TypeDef].rhs.asInstanceOf[TypeBoundsTree].hi
                    // Let's exract the AppliedTypeTree from this Existential and replace the type argument
                    // with the upper bound of the where clause
                    val innerType = tpt.tpt.asInstanceOf[AppliedTypeTree]
                    val simplified = AppliedTypeTree(innerType.tpt, List(upperBound))
                    simplified.sampleValue(classDefs, samplePool)
                }
                case tpt: Select => samplePool.get(tpt.name.toString).orElse(assignValueOfCustomType)
                case tpt => samplePool.get(tpt.toString).orElse(assignValueOfCustomType)
            }
        }
    }

    def paramList(valDefs: List[ValDef]):String = {
        // This will remove modifiers and type declarations because these things would not appear in a param
        // list at the call site
        // Specifically this is necessary beacuse valDefs with default values as returned
        // unchaged in the sample generation process and they have type annotation that we do not want
        val insideParenthesis = valDefs.map( valDef => valDef.name + " = " + prettyPrint(valDef.rhs) ).mkString(", ")
        if (insideParenthesis.isEmpty) "" else s"($insideParenthesis)"
    }

    def anonClass(name: String, impl: List[ValOrDefDef] = Nil) = Block(List(ClassDef(Modifiers(FINAL), newTypeName("$anon"), List(), Template(List(Ident(newTypeName(name))), emptyValDef, List(DefDef(Modifiers(), nme.CONSTRUCTOR, List(), List(List()), TypeTree(), Block(List(Apply(Select(Super(This(tpnme.EMPTY), tpnme.EMPTY), nme.CONSTRUCTOR), List())), Literal(Constant(()))))) ::: impl))), Apply(Select(New(Ident(newTypeName("$anon"))), nme.CONSTRUCTOR), Nil))
    def deconstructAnonymous(tree: Tree): Option[(String, List[ValOrDefDef])] = tree match {
        case block: Block => block.children(0) match {
            case classDef: ClassDef => {
                val parentName = classDef.impl.parents(0).toString
                val body = classDef.impl.body.collect { case valOrDef: ValOrDefDef if !isConstructor(valOrDef) => valOrDef }
                Some((parentName, body))
            }
            case _ => None
        }
        case _ => None
    }

    // I don't like the fact that 2 + 3 = 5 which is why I'd rather
    // start the sample Int values at 3 instead of at 2 in the primeNumbers list

    val sampleIntValues = Math.primeNumbers.drop(1).map(_.toString)
    val sampleStringValues = "\"foo\"" #:: "\"bar\"" #:: "\"biz\"" #:: "\"says\"" #:: "\"Hello\"" #:: "\"World\"" #:: "\"bazinga\"" #:: Stream.empty repeat 
    val alphabet = "abcdefghijklmnopqrstuvwxyz"
    val sampleCharValues = (0 until alphabet.length).map(alphabet.charAt(_)).toStream.repeat.map("\'" + _ + "\'")
    val sampleDoubleValues = Math.primeNumbers.map(_ - 0.5).map(_.toString)
    val sampleFloatValues = sampleDoubleValues.map(_ + "f")
    val sampleBooleanValues = (true #:: false #:: Stream.empty).repeat.map(_.toString)
    //val sampleAnyValValues = sampleIntValues.zip5(sampleStringValues, sampleFloatValues, sampleBooleanValues, sampleCharValues).flatten(_.productIterator)
    val sampleAnyValValues = "3" #:: "\'f\'" #:: "true" #:: Stream.empty repeat
    val sampleAnyValues = "3" #:: "\"foo\"" #:: "true" #:: Stream.empty repeat
    val sampleAnyRefValues = "\"foo\"" #:: "List(3,5,7)" #:: "Some(5)" #:: Stream.empty repeat

    val sampleSeqLengths = 3 #:: 0 #:: 1 #:: 2 #:: Stream.empty repeat
    val sampleSomeOrNone = true #:: false #:: Stream.empty repeat
    
    val simpleValues = Map(
      "Int" -> sampleIntValues,
      "String" -> sampleStringValues,
      "Float" -> sampleFloatValues,
      "Boolean" -> sampleBooleanValues,
      "Long" -> sampleIntValues,
      "Double" -> sampleDoubleValues,
      "Byte" -> sampleIntValues,
      "Short" -> sampleIntValues,
      "Char" -> sampleCharValues,
      "AnyVal" -> sampleAnyValValues,
      "Any" -> sampleAnyValues,
      "AnyRef" -> sampleAnyRefValues
    )

    val parsedSimpleValues = simpleValues.mapValues(stream => stream.map(cm.mkToolBox().parse(_)))
    def createDefaultSamplePool = new SamplePool(parsedSimpleValues, sampleSeqLengths, sampleSomeOrNone)
}

class SamplePool(var values: Map[String, Stream[Tree]], var seqLengths: Stream[Int], var someOrNone: Stream[Boolean]) {

    def get(type_ : String): Option[Tree] =
        values.get(type_).map { stream => // TODO: Remove the race condition here that might cause problems in the future with parallelization
            values = values.updated(type_, stream.tail)
            stream.head
        }

    def nextSeqLength: Int = {
        val result = seqLengths.head
        seqLengths = seqLengths.tail
        result
    }

    def nextOptionIsSome: Boolean = {
        val result = someOrNone.head
        someOrNone = someOrNone.tail
        result
    }
}