package org.adridadou.openlaw.parser

import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger

import org.adridadou.openlaw.{
  OpenlawMap,
  OpenlawString,
  createConcurrentMutableBuffer
}
import org.adridadou.openlaw.parser.template._
import org.adridadou.openlaw.parser.template.variableTypes._
import org.adridadou.openlaw.result.{Failure, Result, Success}
import org.adridadou.openlaw.result.Implicits.{
  RichResult,
  RichResultNel,
  failureCause2Exception
}
import org.adridadou.openlaw.values.TemplateParameters
import org.adridadou.openlaw.vm.OpenlawExecutionEngine
import org.scalatest._
import org.scalatest.EitherValues._
import org.scalatest.OptionValues._

/**
  * Created by davidroon on 05.05.17.
  */
class OpenlawTemplateLanguageParserSpec extends FlatSpec with Matchers {

  private val service = new OpenlawTemplateLanguageParserService()
  private val engine = new OpenlawExecutionEngine

  private val emptyExecutionResult = OpenlawExecutionState(
    parameters = TemplateParameters(),
    id = TemplateExecutionResultId("@@anonymous_main_template_id@@"),
    info = OLInformation(),
    template = CompiledAgreement(),
    executions = Map.empty,
    anonymousVariableCounter = new AtomicInteger(0),
    executionType = TemplateExecution,
    variableRedefinition = VariableRedefinition(),
    remainingElements = createConcurrentMutableBuffer
  )

  private def structureAgreement(
      text: String,
      p: Map[String, String] = Map.empty,
      templates: Map[TemplateSourceIdentifier, CompiledTemplate] = Map.empty,
      externalCallStructures: Map[ServiceName, IntegratedServiceDefinition] =
        Map.empty
  ): Result[StructuredAgreement] =
    compiledTemplate(text).flatMap({
      case agreement: CompiledAgreement =>
        val params = p.map({ case (k, v) => VariableName(k) -> v })
        engine
          .execute(
            agreement,
            TemplateParameters(params),
            templates,
            externalCallStructures
          )
          .flatMap(agreement.structuredMainTemplate(_, (_, _) => None))
      case _ =>
        Failure("was expecting agreement")
    })

  private def executeTemplate(
      text: String,
      p: Map[String, String] = Map.empty,
      templates: Map[TemplateSourceIdentifier, CompiledTemplate] = Map.empty,
      externalCallStructures: Map[ServiceName, IntegratedServiceDefinition] =
        Map.empty
  ): Result[OpenlawExecutionState] =
    compiledTemplate(text).flatMap({
      case agreement: CompiledAgreement =>
        val params = p.map({ case (k, v) => VariableName(k) -> v })
        engine.execute(
          agreement,
          TemplateParameters(params),
          templates,
          externalCallStructures = externalCallStructures
        )
      case _ =>
        Failure("was expecting agreement")
    })

  private def compiledTemplate(text: String): Result[CompiledTemplate] =
    service.compileTemplate(text)

  private def compiledAgreement(text: String): Result[CompiledAgreement] =
    compiledTemplate(text) match {
      case Right(agreement: CompiledAgreement) => Right(agreement)
      case Right(_)                            => Failure("was expecting agreement")
      case Left(ex)                            => Failure(ex)
    }

  private def forReview(
      text: String,
      params: Map[String, String] = Map.empty
  ): Result[String] =
    structureAgreement(text, params).map(service.forReview)
  private def forPreview(
      text: String,
      params: Map[String, String] = Map.empty
  ): Result[String] =
    structureAgreement(text, params).map(service.forPreview)

  private def resultShouldBe(result: Result[String], expected: String): Unit =
    result match {
      case Right(actual) if actual === expected =>
      case Right(actual) =>
        throw new RuntimeException(s"$actual should be $expected")
      case Failure(e, message) => throw new RuntimeException(message, e)
    }

  "Markdown parser service" should "handle tables" in {
    val text =
      """| head1 | head2 | head3 |
         | ----- | ----- | ----- |
         | val11 | val12 | val13 |
         | val21 | val22 | val23 |"""

    val template = service.compileTemplate(text).right.value
    template shouldBe a[CompiledAgreement]

    val tableElement =
      structureAgreement(text).map(_.paragraphs.head.elements.head).right.value
    tableElement shouldBe a[TableElement]
    resultShouldBe(
      forReview(text),
      """<p class="no-section"><table class="markdown-table"><tr class="markdown-table-row"><th class="markdown-table-header align-left border-show">head1</th><th class="markdown-table-header align-left border-show">head2</th><th class="markdown-table-header align-left border-show">head3</th></tr><tr class="markdown-table-row"><td class="markdown-table-data align-left border-show">val11</td><td class="markdown-table-data align-left border-show">val12</td><td class="markdown-table-data align-left border-show">val13</td></tr><tr class="markdown-table-row"><td class="markdown-table-data align-left border-show">val21</td><td class="markdown-table-data align-left border-show">val22</td><td class="markdown-table-data align-left border-show">val23</td></tr></table></p>"""
    )
  }

  it should "be able to define styling of tables with no header" in {
    val text =
      """| ===== | ===== | ===== |
         | val11 | val12 | val13 |
         | val21 | val22 | val23 |"""

    val template = service.compileTemplate(text).getOrThrow()
    template shouldBe a[CompiledAgreement]

    val tableElement =
      structureAgreement(text).map(_.paragraphs.head.elements.head).getOrThrow()
    tableElement shouldBe a[TableElement]

    resultShouldBe(
      forReview(text),
      """<p class="no-section"><table class="markdown-table"><tr class="markdown-table-row"></tr><tr class="markdown-table-row"><td class="markdown-table-data align-left border-hide">val11</td><td class="markdown-table-data align-left border-hide">val12</td><td class="markdown-table-data align-left border-hide">val13</td></tr><tr class="markdown-table-row"><td class="markdown-table-data align-left border-hide">val21</td><td class="markdown-table-data align-left border-hide">val22</td><td class="markdown-table-data align-left border-hide">val23</td></tr></table></p>""".stripMargin
    )
  }

  it should "handle tables following other elements" in {
    val text =
      """This is a test.
    || head1 | head2 | head3 |
    || ----- | ----- | ----- |
    || val11 | val12 | val13 |
    || val21 | val22 | val23 |
    |""".stripMargin

    val structure = structureAgreement(text)
    structure
      .map(_.paragraphs.head.elements(1))
      .right
      .value shouldBe a[TableElement]
  }

  it should "handle tables mixed with other elements with pipes" in {
    val text =
      """This is | a test.
    || head1 | head2 | head3 |
    || ----- | ----- | ----- |
    || val11 | val12 | val13 |
    || val21 | val22 | val23 |
    |This is a test.""".stripMargin

    val structure = structureAgreement(text)
    structure
      .map(_.paragraphs.head.elements(3))
      .right
      .value shouldBe a[TableElement]
  }

  it should "handle tables with variables in cells" in {
    val text =
      """This is | a test.
    || head1 | head2 | head3 |
    || ----- | ----- | ----- |
    || [[var1]] | val12 | val13 |
    || val21 | val22 | val23 |
    |This is a test.""".stripMargin

    structureAgreement(text)
      .map(_.paragraphs.head.elements(3))
      .right
      .value match {
      case tableElement: TableElement =>
        tableElement.rows.head.head.head shouldBe a[VariableElement]
      case _ => fail("not a table element!")
    }
  }

  it should "handle tables with variables in multiple rows" in {
    val text =
      """This is | a test.
    || head1 | head2 | head3 |
    || ----- | ----- | ----- |
    || [[var1]] | val12 | val13 |
    || val21 | val22 | val23 |
    || [[var31]] | val32 | val33 |
    || val41 | val42 | val43 |
    |This is a test.""".stripMargin

    structureAgreement(text)
      .map(_.paragraphs.head.elements(3))
      .right
      .value match {
      case tableElement: TableElement =>
        tableElement.rows.head.head.head shouldBe a[VariableElement]
        tableElement.rows(1).head.head should not be a[VariableElement]
        tableElement.rows(2).head.head shouldBe a[VariableElement]
        tableElement.rows(3).head.head should not be a[VariableElement]
      case _ => fail("not a table element!")
    }

  }

  it should "handle tables with conditionals in cells" in {
    val text =
      """This is | a test.
    || head1 | head2 | head3 |
    || ----- | ----- | ----- |
    || {{conditional1 "Question" => Question text}} | val12 | val13 |
    || val21 | val22 | val23 |
    |This is a test.""".stripMargin

    structureAgreement(text, Map("conditional1" -> "true"))
      .map(_.paragraphs.head.elements(3))
      .right
      .value match {
      case tableElement: TableElement =>
        tableElement.rows.head.head.head shouldBe a[ConditionalStart]
      case _ => fail("not table element!")
    }

  }

  it should "parse and replace each variable with its value" in {

    val clauseText =
      "This is my clause. [[contractor]]. And I am born in [[contractorBirthdate]]"

    resultShouldBe(
      forReview(
        clauseText,
        Map(
          "contractor" -> "My contractor name",
          "contractorBirthdate" -> "January 13th 1983"
        )
      ),
      """<p class="no-section">This is my clause. My contractor name. And I am born in January 13th 1983</p>"""
    )
  }

  it should "compile the document and the compiled version can be then parsed" in {
    val clauseText =
      "This is my clause. [[contractor]]. And I am born in [[contractorBirthdate]]"
    val parameters = Map(
      "contractor" -> "My contractor name",
      "contractorBirthdate" -> "January 13th 1983"
    )

    resultShouldBe(
      forReview(clauseText, parameters),
      """<p class="no-section">This is my clause. My contractor name. And I am born in January 13th 1983</p>"""
    )
  }

  it should "be able to extract the variable definitions" in {

    val clauseText =
      "This is my clause. [[contractor \"the contractor who is going to do the job\"]]. And I am born in [[contractorBirthdate:Date \"The birthdate of the contractor\"]]"

    compiledAgreement(clauseText) match {
      case Right(compiledVersion) =>
        val variables = compiledVersion.block.variables()
        variables.head shouldBe VariableDefinition(
          VariableName("contractor"),
          None,
          Some("the contractor who is going to do the job"),
          None
        )
        variables(1) shouldBe VariableDefinition(
          VariableName("contractorBirthdate"),
          Some(VariableTypeDefinition(DateType.name)),
          Some("The birthdate of the contractor"),
          None
        )
      case Left(ex) => fail(ex)
    }
  }

  it should "handle conditional blocks" in {

    val clauseText =
      "This is my clause. [[contractor:Text \"the contractor who is going to do the job\"]]. {{shouldShowBirthdate \"Should we show the birthdate?\" => And I am born in [[contractorBirthdate \"The birthdate of the contractor\" ]]}}"

    resultShouldBe(
      forReview(
        clauseText,
        Map(
          "contractor" -> "David Roon",
          "shouldShowBirthdate" -> "true",
          "contractorBirthdate" -> "01.13.1983"
        )
      ),
      """<p class="no-section">This is my clause. David Roon. And I am born in 01.13.1983</p>"""
    )

    resultShouldBe(
      forReview(
        clauseText,
        Map(
          "contractor" -> "David Roon",
          "shouldShowBirthdate" -> "false"
        )
      ),
      """<p class="no-section">This is my clause. David Roon. </p>"""
    )
  }

  it should "handle conditional blocks with else" in {

    val clauseText =
      """This is my clause. [[contractor:Text "the contractor who is going to do the job"]]. {{shouldShowBirthdate "Should we show the birthdate?" => And I am born in [[contractorBirthdate "The birthdate of the contractor"]] :: I am not showing any birthday-related information }}"""

    resultShouldBe(
      forReview(
        clauseText,
        Map(
          "contractor" -> "David Roon",
          "shouldShowBirthdate" -> "true",
          "contractorBirthdate" -> "01.13.1983"
        )
      ),
      """<p class="no-section">This is my clause. David Roon. And I am born in 01.13.1983 </p>"""
    )

    resultShouldBe(
      forReview(
        clauseText,
        Map(
          "contractor" -> "David Roon",
          "shouldShowBirthdate" -> "false"
        )
      ),
      """<p class="no-section">This is my clause. David Roon. I am not showing any birthday-related information </p>"""
    )
  }

  it should "do post processing for lists" in {
    val text =
      """a small title
        |
        |^this is a first element
        |^this is a second element
        |^^this is a first sub element
        |^^^this is a first sub sub element
        |^^^this is a second sub sub element
        |^this is a third element
        |^^this is yet another sub element
      """.stripMargin

    val text2 =
      """<div class="openlaw-paragraph paragraph-1"><p class="no-section">a small title</p></div><ul class="list-lvl-1"><li><div class="openlaw-paragraph paragraph-2"><p>1. this is a first element<br /></p></div></li><li><div class="openlaw-paragraph paragraph-3"><p>2. this is a second element<br /></p></div><ul class="list-lvl-2"><li><div class="openlaw-paragraph paragraph-4"><p>(a) this is a first sub element<br /></p></div><ul class="list-lvl-3"><li><div class="openlaw-paragraph paragraph-5"><p>(i) this is a first sub sub element<br /></p></div></li><li><div class="openlaw-paragraph paragraph-6"><p>(ii) this is a second sub sub element<br /></p></div></li></ul></li></ul></li><li><div class="openlaw-paragraph paragraph-7"><p>3. this is a third element<br /></p></div><ul class="list-lvl-2"><li><div class="openlaw-paragraph paragraph-8"><p>(a) this is yet another sub element<br />      </p></div></li></ul></li></ul>""".stripMargin

    val result = forPreview(text)
    resultShouldBe(result, text2)
  }

  it should "not add paragraphs to lists by default" in {
    val text =
      """a small title
        |
        |^I should have a ul tag.
        |^So should I.
        |^And I also.
        |\sectionbreak
        |But I should not!
        |
      """.stripMargin

    val text2 =
      """<div class="openlaw-paragraph paragraph-1"><p class="no-section">a small title</p></div><ul class="list-lvl-1"><li><div class="openlaw-paragraph paragraph-2"><p>1. I should have a ul tag.<br /></p></div></li><li><div class="openlaw-paragraph paragraph-3"><p>2. So should I.<br /></p></div></li><li><div class="openlaw-paragraph paragraph-4"><p>3. And I also.<br /></p></div></li></ul><div class="openlaw-paragraph paragraph-5"><p class="no-section"><hr class="section-break" /></p></div><div class="openlaw-paragraph paragraph-6"><p class="no-section">But I should not!<br /><br />    </p></div>"""

    val result = forPreview(text)
    resultShouldBe(result, text2)
  }

  it should "break the page with a pagebreak tag" in {
    val text =
      """some text
        |
        |\pagebreak
        |
        |more text""".stripMargin

    val text2 =
      """<div class="openlaw-paragraph paragraph-1"><p class="no-section">some text</p></div><div class="openlaw-paragraph paragraph-2"><p class="no-section"><hr class="pagebreak" /></p></div><div class="openlaw-paragraph paragraph-3"><p class="no-section">more text</p></div>"""

    val result = forPreview(text)
    resultShouldBe(result, text2)
  }

  it should "close li elements properly" in {
    val text =
      """[[Id:Identity]]
        |
        |^ **Services**. This is a __test__.
        |""".stripMargin

    val text2 =
      """<p class="no-section"></p><ul class="list-lvl-1"><li><p>1.  <strong>Services</strong>. This is a <u>test</u>.<br /></p></li></ul>"""

    val text3 =
      """<div class="openlaw-paragraph paragraph-1"><p class="no-section"></p></div><ul class="list-lvl-1"><li><div class="openlaw-paragraph paragraph-2"><p>1.  <strong>Services</strong>. This is a <u>test</u>.<br /></p></div></li></ul>"""

    resultShouldBe(forReview(text), text2)
    resultShouldBe(forPreview(text), text3)
  }

  it should "handle link variables with absolute URLs" in {
    val text = """[[link1:Link(label:'homepage';url:'https://openlaw.io')]]"""

    resultShouldBe(
      forPreview(text),
      "<div class=\"openlaw-paragraph paragraph-1\"><p class=\"no-section\"><span class=\"markdown-variable markdown-variable-link1\"><a href=\"https://openlaw.io\">homepage</a></span></p></div>"
    )
    resultShouldBe(
      forReview(text),
      "<p class=\"no-section\"><a href=\"https://openlaw.io\">homepage</a></p>"
    )

  }

  it should "handle link variables with relative URLs, including storing variable correctly" in {
    val text = """[[link1:Link(label: 'Log In';url:'/login')]]"""

    executeTemplate(text) match {
      case Success(executionResult) =>
        executionResult.getVariables(LinkType).size shouldBe 1

        val link = executionResult
          .getVariableValues[LinkInfo](LinkType)
          .right
          .value
          .head
          .underlying
        link should be(LinkInfo("Log In", "/login"))

        resultShouldBe(
          forPreview(text),
          "<div class=\"openlaw-paragraph paragraph-1\"><p class=\"no-section\"><span class=\"markdown-variable markdown-variable-link1\"><a href=\"/login\">Log In</a></span></p></div>"
        )
        resultShouldBe(
          forReview(text),
          "<p class=\"no-section\"><a href=\"/login\">Log In</a></p>"
        )
      case Left(ex) => fail(ex)
    }
  }

  it should "do post processing for lists on preview too (with paragraphs)" in {
    val text =
      """
        |a small title
        |^this is a first element
        |^this is a second element
        |^^this is a first sub element
        |^^^this is a first sub sub element
        |^^^^this is a first sub sub sub element
        |^^^^this is a second sub sub sub element
        |^^^this is a second sub sub element
        |^this is a third element
        |^^this is yet another sub element
      """.stripMargin

    val text2 =
      """<div class="openlaw-paragraph paragraph-1"><p class="no-section"><br />a small title<br /></p></div><ul class="list-lvl-1"><li><div class="openlaw-paragraph paragraph-2"><p>1. this is a first element<br /></p></div></li><li><div class="openlaw-paragraph paragraph-3"><p>2. this is a second element<br /></p></div><ul class="list-lvl-2"><li><div class="openlaw-paragraph paragraph-4"><p>(a) this is a first sub element<br /></p></div><ul class="list-lvl-3"><li><div class="openlaw-paragraph paragraph-5"><p>(i) this is a first sub sub element<br /></p></div><ul class="list-lvl-4"><li><div class="openlaw-paragraph paragraph-6"><p>(1) this is a first sub sub sub element<br /></p></div></li><li><div class="openlaw-paragraph paragraph-7"><p>(2) this is a second sub sub sub element<br /></p></div></li></ul></li><li><div class="openlaw-paragraph paragraph-8"><p>(ii) this is a second sub sub element<br /></p></div></li></ul></li></ul></li><li><div class="openlaw-paragraph paragraph-9"><p>3. this is a third element<br /></p></div><ul class="list-lvl-2"><li><div class="openlaw-paragraph paragraph-10"><p>(a) this is yet another sub element<br />      </p></div></li></ul></li></ul>""".stripMargin

    val result = forPreview(text)
    resultShouldBe(result, text2)
  }

  it should "handle image variables with URLs" in {
    val text =
      """[[Image1:Image("https://openlaw.io/static/img/pizza-dog-optimized.svg")]]"""

    executeTemplate(text) match {
      case Right(executionResult) =>
        executionResult.getVariables(ImageType).size shouldBe 1

        val image = executionResult
          .getVariableValues[OpenlawString](ImageType)
          .right
          .value
          .head
          .underlying
        image should be("https://openlaw.io/static/img/pizza-dog-optimized.svg")

        resultShouldBe(
          forPreview(text),
          "<div class=\"openlaw-paragraph paragraph-1\"><p class=\"no-section\"><span class=\"markdown-variable markdown-variable-Image1\"><img class=\"markdown-embedded-image\" src=\"https://openlaw.io/static/img/pizza-dog-optimized.svg\" /></span></p></div>"
        )
        resultShouldBe(
          forReview(text),
          "<p class=\"no-section\"><img class=\"markdown-embedded-image\" src=\"https://openlaw.io/static/img/pizza-dog-optimized.svg\" /></p>"
        )
      case Left(ex) => fail(ex)
    }
  }

  it should "parse for smart contract calls" in {
    val text = """
      |[[Var1:Text]]
      |[[Var2:Text]]
      |[[Var3:Text]]
      |[[My Contract Call:EthereumCall(
      |contract:"0xde0B295669a9FD93d5F28D9Ec85E40f4cb697BAe";
      |interface:'ipfs:5ihruiherg34893zf';
      |startDate: '2018-12-12 00:00:00';
      |function:'callFunction';
      |arguments:Var1,Var2,Var3;
      |repeatEvery:'1 minute 12 seconds')]]
    """.stripMargin

    executeTemplate(text) match {
      case Right(executionResult) =>
        executionResult.getVariables(EthereumCallType).size shouldBe 1
        val allActions = executionResult.allActions.right.value
        allActions.size shouldBe 1

        val call = executionResult
          .getVariableValues[EthereumSmartContractCall](EthereumCallType)
          .right
          .value
          .head
        call.address.evaluate(emptyExecutionResult) shouldBe Right(
          Some(OpenlawString("0xde0B295669a9FD93d5F28D9Ec85E40f4cb697BAe"))
        )
        call.arguments.map(_.toString) shouldBe List("Var1", "Var2", "Var3")
        call.abi.evaluate(emptyExecutionResult) shouldBe Right(
          Some(OpenlawString("ipfs:5ihruiherg34893zf"))
        )
      case Left(ex) => fail(ex)
    }
  }

  it should "be able to have pipe characters" in {
    val text = "This is a | test."
    resultShouldBe(
      forReview(text, Map("Var" -> "hello world")),
      """<p class="no-section">This is a | test.</p>"""
    )
  }

  it should "handle pipe characters even when conditionals are present" in {
    val text = "{{test \"Ttioje\" => ||a little test||}}"
    resultShouldBe(
      forReview(
        text,
        Map(
          "test" -> "true"
        )
      ),
      """<p class="no-section">||a little test||</p>"""
    )

    resultShouldBe(
      forReview(
        text,
        Map(
          "test" -> "false"
        )
      ),
      ""
    )
  }

  it should "be able to emphasize variables" in {
    val text = "* [[Var]] * ** [[Var]] ** *** [[Var]] ***"
    resultShouldBe(
      forReview(text, Map("Var" -> "hello world")),
      """<p class="no-section"><em> hello world </em> <strong> hello world </strong> <strong><em> hello world </em></strong></p>"""
    )
  }

  it should "be able to underline variables" in {
    val text = "__[[Var]]__"
    resultShouldBe(
      forReview(text, Map("Var" -> "hello world")),
      """<p class="no-section"><u>hello world</u></p>"""
    )
  }

  it should "be able to override section symbols" in {
    resultShouldBe(
      forReview("^ Section 1", Map.empty),
      """<ul class="list-lvl-1"><li><p>1.  Section 1</p></li></ul>"""
    )
    resultShouldBe(
      forReview("^(_(symbol: 'Decimal')) Section 1", Map.empty),
      """<ul class="list-lvl-1"><li><p>1.  Section 1</p></li></ul>"""
    )
    resultShouldBe(
      forReview("^(_(symbol: 'Fake')) Section 1", Map.empty),
      """<ul class="list-lvl-1"><li><p>1.  Section 1</p></li></ul>"""
    )
    resultShouldBe(
      forReview("^(_(symbol: 'LowerLetter')) Section 1", Map.empty),
      """<ul class="list-lvl-1"><li><p>a.  Section 1</p></li></ul>"""
    )
    resultShouldBe(
      forReview("^(_(symbol: 'UpperLetter')) Section 1", Map.empty),
      """<ul class="list-lvl-1"><li><p>A.  Section 1</p></li></ul>"""
    )
    resultShouldBe(
      forReview("^(_(symbol: 'LowerRoman')) Section 1", Map.empty),
      """<ul class="list-lvl-1"><li><p>i.  Section 1</p></li></ul>"""
    )
    resultShouldBe(
      forReview("^(_(symbol: 'UpperRoman')) Section 1", Map.empty),
      """<ul class="list-lvl-1"><li><p>I.  Section 1</p></li></ul>"""
    )
    resultShouldBe(
      forReview("^(_(symbol: 'Hide')) Section 1", Map.empty),
      """<ul class="list-lvl-1"><li><p>  Section 1</p></li></ul>"""
    )
  }

  it should "be able to override section formats" in {
    resultShouldBe(
      forReview("^ Section 1", Map.empty),
      """<ul class="list-lvl-1"><li><p>1.  Section 1</p></li></ul>"""
    )
    resultShouldBe(
      forReview("^(_(format: 'Period')) Section 1", Map.empty),
      """<ul class="list-lvl-1"><li><p>1.  Section 1</p></li></ul>"""
    )
    resultShouldBe(
      forReview("^(_(format: 'Parens')) Section 1", Map.empty),
      """<ul class="list-lvl-1"><li><p>(1)  Section 1</p></li></ul>"""
    )
    resultShouldBe(
      forReview("^(_(format: 'RightParen')) Section 1", Map.empty),
      """<ul class="list-lvl-1"><li><p>1)  Section 1</p></li></ul>"""
    )

    val text =
      """^ Section 1
        |^^(_(format: 'PeriodNested')) Section 1.a
        |""".stripMargin
    resultShouldBe(
      forReview(text, Map.empty),
      """<ul class="list-lvl-1"><li><p>1.  Section 1<br /></p><ul class="list-lvl-2"><li><p>1.a  Section 1.a<br /></p></li></ul></li></ul>"""
    )

    val text2 =
      """^ Section
        |^^ Section
        |^^^ Section
        |^^^^(_(format: 'PeriodNested'))
        |""".stripMargin
    resultShouldBe(
      forReview(text2, Map.empty),
      """<ul class="list-lvl-1"><li><p>1.  Section<br /></p><ul class="list-lvl-2"><li><p>(a)  Section<br /></p><ul class="list-lvl-3"><li><p>(i)  Section<br /></p><ul class="list-lvl-4"><li><p>1.a.i.1 <br /></p></li></ul></li></ul></li></ul></li></ul>"""
    )
  }

  it should "be able to reference sections" in {
    val text =
      """^ Section 1
        |^^ Section 1.a
        |^^^(s1ai) Section 1.a.i
        |
        |[[s1ai]]
      """.stripMargin
    resultShouldBe(
      forReview(text, Map.empty),
      """<ul class="list-lvl-1"><li><p>1.  Section 1<br /></p><ul class="list-lvl-2"><li><p>(a)  Section 1.a<br /></p><ul class="list-lvl-3"><li><p>(i)  Section 1.a.i</p><p>1.a.i<br />      </p></li></ul></li></ul></li></ul>"""
    )
  }

  it should "be able to reference sections with custom symbols and formats" in {
    val text =
      """^ Section 1
        |^^ Section 1.a
        |^^^(s1ai(symbol:'Decimal';format:'Period')) Section 1.a.i
        |
        |[[s1ai]]
      """.stripMargin
    resultShouldBe(
      forReview(text, Map.empty),
      """<ul class="list-lvl-1"><li><p>1.  Section 1<br /></p><ul class="list-lvl-2"><li><p>(a)  Section 1.a<br /></p><ul class="list-lvl-3"><li><p>1.  Section 1.a.i</p><p>1.a.1<br />      </p></li></ul></li></ul></li></ul>"""
    )
  }

  it should "be able to override section symbols and formats" in {
    resultShouldBe(
      forReview("^ Section 1", Map.empty),
      """<ul class="list-lvl-1"><li><p>1.  Section 1</p></li></ul>"""
    )
    resultShouldBe(
      forReview(
        "^(_(symbol: 'UpperRoman'; format: 'Period')) Section 1",
        Map.empty
      ),
      """<ul class="list-lvl-1"><li><p>I.  Section 1</p></li></ul>"""
    )
    resultShouldBe(
      forReview(
        "^(_(symbol: 'LowerLetter'; format: 'Parens')) Section 1",
        Map.empty
      ),
      """<ul class="list-lvl-1"><li><p>(a)  Section 1</p></li></ul>"""
    )
    resultShouldBe(
      forReview(
        "^(_(symbol: 'UpperLetter'; format: 'RightParen')) Section 1",
        Map.empty
      ),
      """<ul class="list-lvl-1"><li><p>A)  Section 1</p></li></ul>"""
    )
    resultShouldBe(
      forReview(
        "^(_(symbol: 'Hide'; format: 'RightParen')) Section 1",
        Map.empty
      ),
      """<ul class="list-lvl-1"><li><p>  Section 1</p></li></ul>"""
    )
  }

  it should "be able to override subsequent section symbols and formats" in {
    resultShouldBe(
      forReview("^ Section 1^ Section 2", Map.empty),
      """<ul class="list-lvl-1"><li><p>1.  Section 1</p></li><li><p>2.  Section 2</p></li></ul>"""
    )
    resultShouldBe(
      forReview(
        "^(_(symbol: 'LowerLetter'; format: 'Parens')) Section 1^ Section 2",
        Map.empty
      ),
      """<ul class="list-lvl-1"><li><p>(a)  Section 1</p></li><li><p>(b)  Section 2</p></li></ul>"""
    )
    resultShouldBe(
      forReview(
        "^(_(symbol: 'LowerLetter')) Section 1^ Section 2^(_(format: 'Parens')) Section 3^ Section 4",
        Map.empty
      ),
      """<ul class="list-lvl-1"><li><p>a.  Section 1</p></li><li><p>b.  Section 2</p></li><li><p>(c)  Section 3</p></li><li><p>(d)  Section 4</p></li></ul>"""
    )
  }

  it should "not be able to emphasize sections" in {
    resultShouldBe(
      forReview("* ^ Section 1 *", Map.empty),
      """<p class="no-section">* </p><ul class="list-lvl-1"><li><p>1.  Section 1 *</p></li></ul>"""
    )
    resultShouldBe(
      forReview("** ^ Section 1 **", Map.empty),
      """<p class="no-section">** </p><ul class="list-lvl-1"><li><p>1.  Section 1 **</p></li></ul>"""
    )
    resultShouldBe(
      forReview("*** ^ Section 1 ***", Map.empty),
      """<p class="no-section">*** </p><ul class="list-lvl-1"><li><p>1.  Section 1 ***</p></li></ul>"""
    )
  }

  it should "not be able to emphasize conditionals" in {
    resultShouldBe(
      forReview(
        "* <%[[var1:Number]]%>{{var1 > 0 => test}} *",
        Map("var1" -> "1")
      ),
      """<p class="no-section">* test *</p>"""
    )
    resultShouldBe(
      forReview(
        "** <%[[var1:Number]]%>{{var1 > 0 => test}} **",
        Map("var1" -> "1")
      ),
      """<p class="no-section">** test **</p>"""
    )
    resultShouldBe(
      forReview(
        "*** <%[[var1:Number]]%>{{var1 > 0 => test}} ***",
        Map("var1" -> "1")
      ),
      """<p class="no-section">*** test ***</p>"""
    )
  }

  it should "not be able to emphasize across newlines" in {
    resultShouldBe(
      forReview("* This is \n text. *", Map.empty),
      """<p class="no-section">* This is <br /> text. *</p>"""
    )
    resultShouldBe(
      forReview("** This is \n text. **", Map.empty),
      """<p class="no-section">** This is <br /> text. **</p>"""
    )
    resultShouldBe(
      forReview("*** This is \n text. ***", Map.empty),
      """<p class="no-section">*** This is <br /> text. ***</p>"""
    )
  }

  it should "parse unterminated emphasis as a normal star character" in {
    val text = "lorem * ipsum"
    resultShouldBe(
      forReview(text),
      """<p class="no-section">lorem * ipsum</p>"""
    )
  }

  it should "parse eth address and render them properly" in {
    val text = "[[my address:EthAddress]]"
    resultShouldBe(
      forReview(
        text,
        Map("my address" -> "0x30c6738E9A5CC946D6ae1f176Dc69Fa1663b3b2C")
      ),
      """<p class="no-section">30c6738e9a5cc946d6ae1f176dc69fa1663b3b2c</p>"""
    )
  }

  it should "accept expressions for conditional blocks greater than" in {
    val text =
      """<%[[var1:Number]][[var2:Number]]%>{{var1 > var2 => iojiwofjiowejf iwjfiowejfiowejfiowejfiowefj}}""".stripMargin
    resultShouldBe(
      forReview(text, Map("var1" -> "112", "var2" -> "16")),
      """<p class="no-section">iojiwofjiowejf iwjfiowejfiowejfiowejfiowefj</p>"""
    )
  }

  it should "accept expressions for conditional blocks greater or equal" in {
    val text =
      """<%[[var1:Number]][[var2:Number]]%>{{var1 >= var2 => iojiwofjiowejf iwjfiowejfiowejfiowejfiowefj}}""".stripMargin
    resultShouldBe(
      forReview(text, Map("var1" -> "112", "var2" -> "16")),
      """<p class="no-section">iojiwofjiowejf iwjfiowejfiowejfiowejfiowefj</p>"""
    )
    resultShouldBe(
      forReview(text, Map("var1" -> "16", "var2" -> "16")),
      """<p class="no-section">iojiwofjiowejf iwjfiowejfiowejfiowejfiowefj</p>"""
    )
    resultShouldBe(forReview(text, Map("var1" -> "15", "var2" -> "16")), "")
  }

  it should "accept expressions with just a variable" in {
    val text =
      """{{var1 "this is a variable" => iojiwofjiowejf iwjfiowejfiowejfiowejfiowefj}}""".stripMargin
    resultShouldBe(
      forReview(text, Map("var1" -> "true")),
      """<p class="no-section">iojiwofjiowejf iwjfiowejfiowejfiowejfiowefj</p>"""
    )
  }

  it should "accept expressions for conditional blocks lesser than" in {
    val text =
      """[[#var1:Number]][[#var2:Number]]{{var1 < var2 => iojiwofjiowejf iwjfiowejfiowejfiowejfiowefj}}""".stripMargin

    resultShouldBe(
      forReview(text, Map("var1" -> "12", "var2" -> "16")),
      """<p class="no-section">iojiwofjiowejf iwjfiowejfiowejfiowejfiowefj</p>"""
    )
  }

  it should "accept nested expressions for conditional" in {
    val text =
      """<%[[var1:Number]] [[var2:Number]]%>{{(var1 < var2) && (var1 < var2) => iojiwofjiowejf iwjfiowejfiowejfiowejfiowefj}}""".stripMargin

    resultShouldBe(
      forReview(text, Map("var1" -> "12", "var2" -> "116")),
      """<p class="no-section">iojiwofjiowejf iwjfiowejfiowejfiowejfiowefj</p>"""
    )
  }

  it should "be able to define a default value" in {
    val clauseText =
      "This is my clause. [[contractor:Text(\"Hello my friend\")]]"

    executeTemplate(clauseText) match {
      case Success(r) =>
        r.getVariable("contractor").flatMap(_.defaultValue) match {
          case Some(OneValueParameter(StringConstant(str))) =>
            str shouldBe "Hello my friend"
          case result => fail(result.toString)
        }
      case Failure(ex, message) =>
        fail(message, ex)
    }
  }

  it should "be able to define multiple default values" in {
    val clauseText =
      "This is my clause. [[contractor:Choice(\"First option\", \"Second option\")]]"

    executeTemplate(clauseText).toOption
      .flatMap(_.getVariable("contractor").flatMap(_.defaultValue)) match {
      case Some(ListParameter(vector)) =>
        val list = vector.map(_.evaluate(emptyExecutionResult))
        list.map({ case Success(Some(value)) => value }) shouldBe List(
          "First option",
          "Second option"
        )
      case result =>
        fail(result.toString)
    }
  }

  it should "not be able to define a default value for a number that is not a number" in {
    val clauseText =
      "This is my clause. [[contractor:Number(\"Hello my friend\")]]"

    structureAgreement(clauseText) match {
      case Left(ex) =>
        ex.message shouldBe "the constructor type should be Number but is Text"
      case Right(_) => fail("should fail")
    }
  }

  it should "be able to define a default value for a number " in {
    val clauseText = "This is my clause. [[contractor:Number(24)]]"

    executeTemplate(clauseText) match {
      case Right(executionResult) =>
        executionResult.getVariable("contractor") match {
          case Some(variable) =>
            variable.defaultValue match {
              case Some(OneValueParameter(NumberConstant(n))) =>
                n shouldBe BigDecimal("24")
              case something =>
                fail("default value is not correct:" + something)
            }
          case None => fail("variable not found")
        }
      case Left(ex) => fail(ex)
    }
  }

  it should "be able to define a default value for a date by parsing the date" in {
    val clauseText = "This is my clause. [[contractor:Date(\"2017-06-24\")]]"
    executeTemplate(clauseText) match {
      case Right(executionResult) =>
        executionResult.getVariable("contractor") match {
          case Some(variable) =>
            variable.defaultValue match {
              case Some(OneValueParameter(StringConstant(text))) =>
                text shouldBe "2017-06-24"
              case something =>
                fail("default value is not correct:" + something)
            }
          case None => fail("contractor variable not found")
        }
      case Failure(ex, message) => fail(message, ex)
    }
  }

  it should "be able to define a default value for a date time by parsing the date" in {
    val clauseText =
      "This is my clause. [[contractor:DateTime(\"2017-06-24 13:45:00\")]]"

    executeTemplate(clauseText) match {
      case Right(executionResult) =>
        executionResult
          .getVariable("contractor")
          .flatMap(_.defaultValue) match {
          case Some(OneValueParameter(StringConstant(text))) =>
            text shouldBe "2017-06-24 13:45:00"
          case something => fail("default value is not correct:" + something)
        }
      case Failure(ex, message) =>
        fail(message, ex)
    }
  }

  it should "boolean with composable" in {
    val text =
      """<%[[var1:YesNo]][[var2:YesNo]]%>{{var1 && var2 => iojiwofjiowejf iwjfiowejfiowejfiowejfiowefj}}""".stripMargin

    resultShouldBe(
      forReview(text, Map("var1" -> "true", "var2" -> "true")),
      """<p class="no-section">iojiwofjiowejf iwjfiowejfiowejfiowejfiowefj</p>"""
    )
  }

  it should "be able to use constants in comparaison expressions" in {
    val text =
      """[[#var1:Number]]{{var1 > 10 => iojiwofjiowejf iwjfiowejfiowejfiowejfiowefj}}""".stripMargin

    resultShouldBe(
      forReview(text, Map("var1" -> "12")),
      """<p class="no-section">iojiwofjiowejf iwjfiowejfiowejfiowejfiowefj</p>"""
    )
  }

  it should "be able to use constants in comparaison expressions with equals too" in {
    val text =
      """[[#var1:Number]]{{var1 = 12 => iojiwofjiowejf iwjfiowejfiowejfiowejfiowefj}}""".stripMargin

    resultShouldBe(
      forReview(text, Map("var1" -> "12")),
      """<p class="no-section">iojiwofjiowejf iwjfiowejfiowejfiowejfiowefj</p>"""
    )
  }

  it should "be able to use constants in equals expressions" in {
    val text =
      """[[#var1:Number]]{{var1 = 10 => iojiwofjiowejf iwjfiowejfiowejfiowejfiowefj}}""".stripMargin

    resultShouldBe(
      forReview(text, Map("var1" -> "10")),
      """<p class="no-section">iojiwofjiowejf iwjfiowejfiowejfiowejfiowefj</p>"""
    )
  }
  it should "be able to use aliasing " in {
    val text =
      """[[#var2:Number]][[@var1 = var2 + 10]]{{var1 > 10 => iojiwofjiowejf iwjfiowejfiowejfiowejfiowefj}}""".stripMargin

    resultShouldBe(
      forReview(text, Map("var2" -> "10")),
      """<p class="no-section">iojiwofjiowejf iwjfiowejfiowejfiowejfiowefj</p>"""
    )
  }

  it should "be able to use aliasing 2" in {
    val text =
      """[[#var2:Number]][[@var1 = 10 + var2]]{{var1 > 10 => iojiwofjiowejf iwjfiowejfiowejfiowejfiowefj}}""".stripMargin

    resultShouldBe(
      forReview(text, Map("var2" -> "10")),
      """<p class="no-section">iojiwofjiowejf iwjfiowejfiowejfiowejfiowefj</p>"""
    )
  }

  it should "be able to use aliasing 3" in {
    val text =
      """[[#var2:Number]][[#var1:Number]][[@var3 = var1 + var2]][[var3]]{{var3 > 39 =>iojiwofjiowejf iwjfiowejfiowejfiowejfiowefj}}""".stripMargin

    resultShouldBe(
      forReview(text, Map("var2" -> "10", "var1" -> "30")),
      """<p class="no-section">40iojiwofjiowejf iwjfiowejfiowejfiowejfiowefj</p>"""
    )
  }

  it should "handle cyclic dependencies" in {
    val text =
      """[[#var1:Number]][[@var2 = var1 + var3]][[@var3 = var1 + var2]][[var1]][[var2]][[var3]]{{var3 > 39 => iojiwofjiowejf iwjfiowejfiowejfiowejfiowefj}}""".stripMargin

    structureAgreement(text) match {
      case Left(ex) =>
        ex.message shouldEqual "alias expression uses undefined variables var3"
      case Right(_) =>
        fail("this should fail")
    }
  }

  it should "handle cyclic dependencies with self dependency" in {
    val text =
      """[[#var1:Number]][[@var2 = var1 + var2]][[var1]][[var2]]""".stripMargin

    structureAgreement(text) match {
      case Left(ex) =>
        ex.message shouldEqual "alias expression uses undefined variables var2"
      case Right(_) =>
        fail("this should fail")
    }
  }

  it should "not see an alias as a forward reference " in {
    val text =
      """[[@var1 = 10]][[@var2 = var1 + 10]][[var1]][[var2]]""".stripMargin

    forReview(text) shouldBe Right("""<p class="no-section">1020</p>""")
  }

  it should "handle cyclic dependencies with only aliases" in {
    val text =
      """
        |[[@a = b + 10]]
        |[[@b = c + 10]]
        |[[@c = a + 10]]
      """.stripMargin

    structureAgreement(text) match {
      case Left(ex) =>
        ex.message shouldEqual "alias expression uses undefined variables b"
      case Right(_) =>
        fail("this should fail")
    }
  }

  it should "handle cyclic dependencies in constructor" in {
    val text =
      """[[var1:Number]][[var2:Number(var1 + var3)]][[var3:Number(var1 + var2)]]""".stripMargin

    structureAgreement(text) match {
      case Left(ex) =>
        ex.message shouldEqual "error while processing the new variable var2. The variable \"var3\" is used in the constructor but has not been defined"
      case Right(_) =>
        fail("this should fail")
    }
  }

  it should "handle code blocks" in {
    val text =
      """<%
        |# this is a comment
        |[[Var1:Number]]
        |[[@Var2 = Var1 + 4]]
        |[[My Contract Address:EthAddress]]
        |[[My Interface:SmartContractMetadata]]
        |[[My Contract:EthereumCall(contract:My Contract Address;
        |interface:My Interface;
        |network:"4";
        |function:"function"
        |)]]
        |%>[[Var2]]""".stripMargin

    resultShouldBe(
      forReview(text, Map("Var1" -> "2202")),
      """<p class="no-section">2,206</p>"""
    )
  }

  it should "handle not logic" in {
    val text =
      """<%[[My Var:YesNo]][[Another:YesNo]]%>{{My Var && !Another => iojiowejfiowejfiowejfioewjf }}""".stripMargin

    executeTemplate(text) match {
      case Right(executionResult) =>
        executionResult.getVariables.map(_.name.name).toSet shouldBe Set(
          "My Var",
          "Another"
        )
      case Left(ex) => fail(ex)
    }
  }

  it should "let you put different quote characters in a string" in {
    val text =
      """{{My Var "that's it!" => iojiowejfiowejfiowejfioewjf }}""".stripMargin

    executeTemplate(text) match {
      case Right(executionResult) =>
        executionResult.getVariables.map(_.name.name).toSet shouldBe Set(
          "My Var"
        )
        executionResult.getVariables.map(_.description).head shouldBe Some(
          "that's it!"
        )
      case Left(ex) => fail(ex)
    }
  }

  it should "throw an error if the variable types used in an alias don't match" in {
    val text =
      """
        [[Var 1:Number]]
        [[Var 2:Text]]
        [[@Bad Var = Var 1 + Var 2]]
        |""".stripMargin

    structureAgreement(text) match {
      case Left(ex) =>
        ex.message shouldBe "left and right expression are of incompatible types.Number & Text in Var 1 & Var 2"
      case Right(_) =>
        fail("should fail")
    }
  }

  it should "let use calculation between periods and dates" in {
    val text =
      """<%[[Var 1:Period]]
        [[Var 2:DateTime]]
        [[@New Date = Var 1 + Var 2]]%>[[New Date]]""".stripMargin

    resultShouldBe(
      forReview(
        text,
        Map(
          "Var 1" -> "1 day",
          "Var 2" -> (ZonedDateTime.now
            .withYear(2018)
            .withMonth(1)
            .withDayOfMonth(1)
            .withHour(10)
            .withMinute(10)
            .withSecond(0)
            .toInstant
            .toEpochMilli
            .toString)
        )
      ),
      """<p class="no-section">January 2, 2018 10:10:00</p>"""
    )
  }

  it should "let use calculation between periods and dates with constants" in {
    val text =
      """<%[[Var 2:DateTime]]
        [[@New Date = Var 2 + "1 day"]]%>[[New Date]]""".stripMargin

    resultShouldBe(
      forReview(
        text,
        Map(
          "Var 1" -> "1 day",
          "Var 2" -> (ZonedDateTime.now
            .withYear(2018)
            .withMonth(1)
            .withDayOfMonth(1)
            .withHour(10)
            .withMinute(10)
            .withSecond(0)
            .toInstant
            .toEpochMilli)
            .toString
        )
      ),
      """<p class="no-section">January 2, 2018 10:10:00</p>"""
    )
  }

  it should "format dates with built in formatters" in {
    resultShouldBe(
      forReview(
        """[[date:DateTime("2017-06-24 13:45:00") | year]]""",
        Map.empty
      ),
      """<p class="no-section">2017</p>"""
    )
    resultShouldBe(
      forReview(
        """[[date:DateTime("2017-06-24 13:45:00") | day]]""",
        Map.empty
      ),
      """<p class="no-section">24</p>"""
    )
    resultShouldBe(
      forReview(
        """[[date:DateTime("2017-06-24 13:45:00") | day_name]]""",
        Map.empty
      ),
      """<p class="no-section">Saturday</p>"""
    )
    resultShouldBe(
      forReview(
        """[[date:DateTime("2017-06-24 13:45:00") | month]]""",
        Map.empty
      ),
      """<p class="no-section">6</p>"""
    )
    resultShouldBe(
      forReview(
        """[[date:DateTime("2017-06-24 13:45:00") | month_name]]""",
        Map.empty
      ),
      """<p class="no-section">June</p>"""
    )
  }

  it should "handle a set of conditionals " in {
    val text =
      """{{
        |{{Condition 1 "This is a condition" => Condition 1}}
        |{{Condition 2 "This is another condition" => Condition 2}}
        |}}""".stripMargin

    resultShouldBe(
      forReview(
        text,
        Map(
          "Condition 1" -> "false",
          "Condition 2" -> "false"
        )
      ),
      ""
    )

    resultShouldBe(
      forReview(
        text,
        Map(
          "Condition 1" -> "true",
          "Condition 2" -> "false"
        )
      ),
      """<p class="no-section">Condition 1</p>"""
    )

    resultShouldBe(
      forReview(
        text,
        Map(
          "Condition 1" -> "false",
          "Condition 2" -> "true"
        )
      ),
      """<p class="no-section">Condition 2</p>"""
    )

    resultShouldBe(
      forReview(
        text,
        Map(
          "Condition 1" -> "true",
          "Condition 2" -> "true"
        )
      ),
      """<p class="no-section">Condition 1</p>"""
    )
  }

  it should "clean the text if there is too many returns" in {
    val text =
      """this is a first line
        |
        |this should not be changed
        |
        |
        |
        |but here yes
        |
        |
        |here too""".stripMargin
    resultShouldBe(
      forReview(text),
      """<p class="no-section">this is a first line</p><p class="no-section">this should not be changed<br /><br /></p><p class="no-section">but here yes<br /></p><p class="no-section">here too</p>""".stripMargin
    )
  }

  it should "be able to break pages" in {
    val text =
      """first paragraph of text
        |\pagebreak
        |
        |second paragraph of text""".stripMargin
    resultShouldBe(
      forReview(text),
      """<p class="no-section">first paragraph of text<br /></p><p class="no-section"><hr class="pagebreak" /></p><p class="no-section">second paragraph of text</p>"""
    )
  }

  it should "drop extraneous newlines in pagebreak tag" in {
    val text =
      """first paragraph of text
        |\pagebreak
        |second paragraph of text""".stripMargin
    resultShouldBe(
      forReview(text),
      """<p class="no-section">first paragraph of text<br /></p><p class="no-section"><hr class="pagebreak" /></p><p class="no-section">second paragraph of text</p>"""
    )
  }

  it should "drop extraneous newlines in sectionbreak tag" in {
    val text =
      """first paragraph of text
      |\sectionbreak
      |second paragraph of text""".stripMargin
    resultShouldBe(
      forReview(text),
      """<p class="no-section">first paragraph of text<br /></p><p class="no-section"><hr class="section-break" /></p><p class="no-section">second paragraph of text</p>"""
    )
  }

  it should "be able to indent lines" in {
    val text =
      """first paragraph of text
      |
      |\indentsecond paragraph of text
      |
      |third paragraph of text""".stripMargin
    resultShouldBe(
      forReview(text),
      """<p class="no-section">first paragraph of text</p><p class="no-section indent">second paragraph of text</p><p class="no-section">third paragraph of text</p>"""
    )
  }

  it should "be able to align lines centered" in {
    val text =
      """first paragraph of text
      |
      |\centeredsecond paragraph of text
      |
      |third paragraph of text""".stripMargin
    resultShouldBe(
      forReview(text),
      """<p class="no-section">first paragraph of text</p><p class="no-section align-center">second paragraph of text</p><p class="no-section">third paragraph of text</p>"""
    )
  }

  it should "be able to align lines to the right" in {
    val text =
      """first paragraph of text
      |
      |\rightsecond paragraph of text
      |
      |third paragraph of text""".stripMargin
    resultShouldBe(
      forReview(text),
      """<p class="no-section">first paragraph of text</p><p class="no-section align-right">second paragraph of text</p><p class="no-section">third paragraph of text</p>"""
    )
  }

  it should "be able to align lines to the right with three-quarters spacing" in {
    val text =
      """first paragraph of text
      |
      |\right-three-quarterssecond paragraph of text
      |
      |third paragraph of text""".stripMargin
    resultShouldBe(
      forReview(text),
      """<p class="no-section">first paragraph of text</p><p class="no-section align-right-three-quarters">second paragraph of text</p><p class="no-section">third paragraph of text</p>"""
    )
  }

  it should "clean the text if there is invisible variables in the middle" in {
    val text =
      """this is a first line
        |
        |this should not be changed
        |[[#Test 1]]
        |[[#Test 2]]
        |
        |but here yes
        |
        |
        |here too""".stripMargin
    resultShouldBe(
      forReview(text),
      """<p class="no-section">this is a first line</p><p class="no-section">this should not be changed<br /><br /></p><p class="no-section">but here yes<br /></p><p class="no-section">here too</p>""".stripMargin
    )
  }

  it should "clean the text if there is a space before a dot" in {
    val text =
      """this is a first line .
        |this is another line ...
        |even with multiple spaces      .""".stripMargin

    resultShouldBe(
      forReview(text),
      """<p class="no-section">this is a first line.<br />this is another line ...<br />even with multiple spaces.</p>""".stripMargin
    )
  }

  // this is the tricky handling for conditionals, make sure that this works properly
  it should "handle properly conditional blocks highlights with sections" in {
    val text = """{{Try "try this logic" => ^ This is a test}}""".stripMargin

    resultShouldBe(
      forPreview(text, Map("Try" -> "true")),
      """<div class="openlaw-paragraph paragraph-1"><p class="no-section"></p></div><ul class="list-lvl-1"><li><div class="openlaw-paragraph paragraph-2"><p>1.  This is a test</p></div></li></ul>"""
    )
  }

  it should "handle decimals in constants as well" in {
    val text =
      """
        |[[#Variable1:Number(0.34)]]
        |[[@My Alias = Variable1 + 0.56]]
        |[[My Alias | noTrailingZeros]]""".stripMargin.replaceAll("\n", "")

    resultShouldBe(
      forReview(text, Map("Variable1" -> "0.34")),
      """<p class="no-section">0.9</p>"""
    )
  }

  it should "handle decimals in divisions" in {
    val text =
      """
        |[[#Variable1:Number]]
        |[[@My Alias = (Variable1 / 100) * 4 ]]
        |[[My Alias | noTrailingZeros]]""".stripMargin.replaceAll("\n", "")

    resultShouldBe(
      forReview(text, Map("Variable1" -> "34")),
      """<p class="no-section">1.36</p>"""
    )
  }

  it should "be able to organize sections for variables" in {
    val text =
      """
        |==My first section==
        |[[Variable 1:Number]]
        [[Variable 2:Number]]
        |
        |==My second section==
        |[[Variable 3:Number]]
        [[Variable 4:Number]]
        |<%
        |==My second section==
        |[[Variable 5:Number]]
        [[Variable 6:Number]]
        |%>
      """.stripMargin

    executeTemplate(text) match {
      case Right(executionResult) =>
        val sections = executionResult.sections
        sections("My first section").map(_.name) should contain theSameElementsAs Seq(
          "Variable 1",
          "Variable 2"
        )
        sections("My second section").map(_.name) should contain theSameElementsAs Seq(
          "Variable 3",
          "Variable 4",
          "Variable 5",
          "Variable 6"
        )

        executionResult.getVariables
          .map(_.name.name)
          .toSet should contain theSameElementsAs Set(
          "Variable 1",
          "Variable 2",
          "Variable 3",
          "Variable 4",
          "Variable 5",
          "Variable 6"
        )
      case Left(ex) => fail(ex)
    }
  }

  it should "compile this piece" in {
    val text =
      """"==Effective Date==
              [[Effective Date: Date | date]]

                 ==Company Information -
                 What's the basic information for company?==
                 [[Company Name]]
                 [[Company Street]]
                 [[Company City]]
                 [[Company State]]
                 [[Company Zip]]

                 ==Company Signatory
                 Who will be the company signatory?==
                 [[First Name of Company Signatory]]
                 [[Last Name of Company Signatory]]

                 ==Employee Information
                 Enter some basic employee information below==
                 [[Employee First Name]]
                 [[Employee Last Name]]
                 [[Employee Street]]
                 [[Employee City]]
                 [[Employee State]]
                 [[Employee Zip]]
                 [[Additional Agreements]]
                 [[Additional Employee information]]


                 [[Employee Offer Letter: Template("Employee Offer Letter")]]

                 {{Additional Employee information "Do you need additional information?" =>

                 ==Employee Information
                 Enter some basic employee information below==
                 [[Employee Information Plus 1:Text]]
                 }}

                 {{Additional Agreements "Will the employee be signing additional agreements?" => {{Confidentiality Agreement "A confidentiality agreement?" => [[CIAA: Template("Confidential Information and Invention Assignment Agreement")]]}} {{Dispute Resolution "An Alternative Dispute Resolution Agreement?" => [[ADR: Template("Alternative Dispute Resolution Agreement")]]}} }}""""

    compiledTemplate(text)
  }

  it should "work with employee offer letter" in {
    val text =
      """
        |**[[Company Name: Text | Uppercase]]**
        |[[Company Street]]
        |[[Company City]], [[Company State]] [[Company Zip]]
        |
        |[[Effective Date: Date | date]]
        |
        |[[Employee First Name]] [[Employee Last Name]]
        |[[Employee Street]]
        |[[Employee City]], [[Employee State]] [[Employee Zip]]
        |
        |**Re:  Offer Letter**
        |
        |Dear [[Employee First Name]]:
        |
        |We're excited to offer you a position with""".stripMargin

    resultShouldBe(
      forReview(text, Map.empty),
      """<p class="no-section"><br /><strong>[[Company Name]]</strong><br />[[Company Street]]<br />[[Company City]], [[Company State]] [[Company Zip]]</p><p class="no-section">[[Effective Date]]</p><p class="no-section">[[Employee First Name]] [[Employee Last Name]]<br />[[Employee Street]]<br />[[Employee City]], [[Employee State]] [[Employee Zip]]</p><p class="no-section"><strong>Re:  Offer Letter</strong></p><p class="no-section">Dear [[Employee First Name]]:</p><p class="no-section">We're excited to offer you a position with</p>""".stripMargin
    )
  }

  it should "not highlight identity variables" in {
    val text =
      """
        |[[Id: Identity]]
        |
        |_______________________
        |Test
      """.stripMargin

    resultShouldBe(
      forPreview(text = text, params = Map.empty),
      """<div class="openlaw-paragraph paragraph-1"><p class="no-section"><br /></p></div><div class="openlaw-paragraph paragraph-2"><p class="no-section">_______________________<br />Test<br />      </p></div>"""
    )
  }

  it should "not eat some of the content" in {
    val text =
      """
        |[[ConsenSys AG Signatory Email: Identity]]
        |
        |_______________________
        |Joseph Lubin, Mitglied des Verwaltungsrates (Board Member) ConsenSys AG
        |
        |On behalf of the Contractor:
        |
        |[[Contractor Email:  Identity]]
        |_______________________
        |By: [[Contractor First Name]] [[Contractor Last Name]]
      """.stripMargin

    resultShouldBe(
      forReview(text = text, params = Map.empty),
      """<p class="no-section"><br /></p><p class="no-section">_______________________<br />Joseph Lubin, Mitglied des Verwaltungsrates (Board Member) ConsenSys AG</p><p class="no-section">On behalf of the Contractor:</p><p class="no-section"><br />_______________________<br />By: [[Contractor First Name]] [[Contractor Last Name]]<br />      </p>"""
    )
  }

  it should "give you the list of used variables in the template" in {
    val text =
      """<%
        |[[var1:Number]]
        |[[var2:Text]]
        |%>
        |
        |[[var1]]
        |""".stripMargin

    executeTemplate(text) match {
      case Right(executionResult) =>
        executionResult.getAllExecutedVariables.map({
          case (_, name) => name.name
        }) shouldBe Seq("var1")
      case Left(ex) => fail(ex)
    }

  }

  it should "give you the list of used variables in the template even if it is used in a constructor" in {
    val text =
      """<%
        |[[var2:Number]]
        |[[var1:Number(var2)]]
        |%>
        |
        |[[var1]]
        |""".stripMargin

    executeTemplate(text) match {
      case Right(executionResult) =>
        executionResult.getAllExecutedVariables.map({
          case (_, name) => name.name
        }) shouldBe Seq("var1", "var2")
      case Left(ex) => fail(ex)
    }
  }

  it should "give you the list of used variables in the template even if it is used in an alias" in {
    val text =
      """<%
        |[[var2:Number]]
        |[[@var3 = var2 + 10]]
        |[[var1:Number(var3)]]
        |%>
        |
        |[[var1]]
        |""".stripMargin

    executeTemplate(text) match {
      case Right(executionResult) =>
        executionResult.getAllExecutedVariables.map({
          case (_, name) => name.name
        }) shouldBe Seq("var1", "var2")
      case Left(ex) => fail(ex)
    }
  }

  it should "not seen the right part of an expression as executed if it should not with 'and'" in {
    val text =
      """<%
        |[[var1:Number]]
        |[[var2:Number]]
        |%>
        |{{(var1 > 10) && (var2 > 20) => iuhiuhuih}}
        |""".stripMargin

    executeTemplate(text, Map("var1" -> "5", "var2" -> "40")) match {
      case Right(executionResult) =>
        executionResult.getAllExecutedVariables.map({
          case (_, name) => name.name
        }) shouldBe Seq("var1")
      case Left(ex) => fail(ex)
    }
  }

  it should "not seen the right part of an expression as executed if it should not with 'or'" in {
    val text =
      """<%
        |[[var1:Number]]
        |[[var2:Number]]
        |%>
        |{{(var1 > 10) || (var2 > 20) => hihiuhuih }}
        |""".stripMargin

    executeTemplate(text, Map("var1" -> "25", "var2" -> "40")) match {
      case Right(executionResult) =>
        executionResult.getAllExecutedVariables.map({
          case (_, name) => name.name
        }) shouldBe Seq("var1")
      case Left(ex) => fail(ex)
    }
  }

  it should "handle the old conditional syntax" in {
    val text =
      """<%
        |[[var1:Number]]
        |[[var2:Number]]
        |%>
        |{{((var1 > 10) || (var2 > 20)) hihiuhuih }}
        |""".stripMargin

    executeTemplate(text, Map("var1" -> "25", "var2" -> "40")) match {
      case Right(executionResult) =>
        executionResult.getAllExecutedVariables.map({
          case (_, name) => name.name
        }) shouldBe Seq("var1")
      case Left(ex) => fail(ex)
    }
  }

  it should "be able to list a variable that is in bold" in {
    val text = "** [[My Var:Text]] **"

    executeTemplate(text, Map.empty) match {
      case Right(executionResult) =>
        executionResult.getVariables.map(_.name.name) should contain("My Var")
      case Left(ex) => fail(ex)
    }
  }

  it should "take the default value expression if none has been specified" in {
    val text =
      """[[My Number:Number(12)]][[My Number 2:Number(My Number + 10)]]""".stripMargin

    resultShouldBe(
      forReview(text, Map.empty),
      """<p class="no-section">1222</p>"""
    )
  }

  it should "read a property from an address" in {
    val text = "<%[[My Address:Address]]%>[[My Address.country]]"

    resultShouldBe(
      forReview(
        text,
        Map(
          "My Address" -> AddressType
            .internalFormat(
              Address(
                city = "a certain city",
                state = "a state",
                country = "United States",
                zipCode = "102030392",
                formattedAddress = "some kind of formatted address"
              )
            )
            .right
            .value
        )
      ),
      """<p class="no-section">United States</p>"""
    )
  }

  it should "validate and make sure you do not use an invalid property" in {
    val text = "<%[[My Address:Address]]%>[[My Address.badProperty]]"

    structureAgreement(text) match {
      case Right(_) => fail("should fail")
      case Left(msg) =>
        msg.message shouldBe "property 'badProperty' not found for type Address"
    }
  }

  it should "round number" in {
    val text = "<%[[My Number:Number]]%>[[My Number | rounding(2)]]"

    resultShouldBe(
      forReview(text, Map("My Number" -> "0.33333333333")),
      """<p class="no-section">0.33</p>"""
    )
  }

  it should "format number" in {
    val text = "<%[[My Number:Number]]%>[[My Number]]"

    resultShouldBe(
      forReview(text, Map("My Number" -> "1000000000")),
      """<p class="no-section">1,000,000,000</p>"""
    )
  }

  it should "round number with expression" in {
    val text =
      "<%[[My Number:Number]] [[Rounding Number:Number]]%>[[My Number | rounding(Rounding Number)]]"

    resultShouldBe(
      forReview(
        text,
        Map("My Number" -> "0.333333333333333", "Rounding Number" -> "2")
      ),
      """<p class="no-section">0.33</p>"""
    )
  }

  it should "handle validation" in {
    val text =
      """<%
         [[My Number:Number]]
         %>

         [[number validation:Validation(
         condition: My Number > 5;
         errorMessage:"My Number needs to be higher than 5"
         )]]
      """.stripMargin

    executeTemplate(text, Map("My Number" -> "3")) match {
      case Right(executionResult) =>
        executionResult.validate.toResult.left.value.message should be(
          "My Number needs to be higher than 5"
        )
      case Left(ex) => fail(ex.message, ex)
    }
  }

  it should "parse a defined domain type and validate correctly" in {
    val text =
      """
        [[Amount:DomainType(
         |variableType: Number;
         |condition: this > 0;
				 |errorMessage:"amount must be greater than 0!"
				 |)]]
         [[amount:Amount]]
      """.stripMargin

    executeTemplate(text) match {
      case Success(executionResult) =>
        executionResult.findVariableType(VariableTypeDefinition("Amount")) match {
          case Some(domainType: DefinedDomainType) => domainType.domain
          case Some(variableType) =>
            fail(s"invalid variable type ${variableType.thisType}")
          case None => fail("domain type is not the right type")
        }
      case Failure(ex, message) =>
        fail(message, ex)
    }

    executeTemplate(text) match {
      case Right(executionResult) =>
        executionResult.findVariableType(VariableTypeDefinition("Amount")) match {
          case Some(_: DefinedDomainType) =>
            val Right(newExecutionResult) =
              executeTemplate(text, Map("amount" -> "5"))
            service
              .parseExpression("amount")
              .flatMap(_.evaluate(newExecutionResult))
              .right
              .value
              .value
              .toString shouldBe "5"
          case Some(variableType) =>
            fail(s"invalid variable type ${variableType.thisType}")
          case None => fail("domain type is not the right type")
        }
      case Left(ex) =>
        fail(ex)
    }

    executeTemplate(text) match {
      case Right(executionResult) =>
        executionResult.findVariableType(VariableTypeDefinition("Amount")) match {
          case Some(_: DefinedDomainType) =>
            val Success(newExecutionResult) =
              executeTemplate(text, Map("amount" -> "-5"))
            newExecutionResult.validate.toResult.left.value.message should be(
              "amount must be greater than 0!"
            )
          case Some(variableType) =>
            fail(s"invalid variable type ${variableType.thisType}")
          case None => fail("domain type is not the right type")
        }
      case Left(ex) =>
        fail(ex)
    }
  }

  it should "make it possible to do expressions with domain types (and the expressions need to be validated too)" in {
    val text =
      """
        [[Amount:DomainType(
				|variableType: Number;
				|condition: this > 0;
				|errorMessage:"amount must be greater than 0!"
				|)]]
				|[[amount:Amount]]
				|[[amount2:Amount]]
				|[[@amount expression=amount - amount2]]
				|[[amount expression]]
      """.stripMargin

    executeTemplate(text, Map("amount" -> "10", "amount2" -> "2")) match {
      case Success(executionResult) =>
        executionResult.validate.isValid shouldBe true
      case Failure(ex, message) =>
        fail(message, ex)
    }

    executeTemplate(text, Map("amount" -> "10", "amount2" -> "12")) match {
      case Success(executionResult) =>
        executionResult.validate.toResult.left.value.message shouldBe "amount must be greater than 0!"
      case Failure(ex, message) =>
        fail(message, ex)
    }
  }

  it should "verify that conditionals are of the correct type" in {
    val text =
      """
         [[number:Number]]
         {{ number + 401 => should not work}}
      """.stripMargin

    structureAgreement(text) match {
      case Right(_) =>
        fail("should fail")
      case Left(ex) =>
        ex.message shouldBe "Conditional expression number + 401 is of type NumberType instead of YesNo"
    }
  }

  it should "verify that conditionals work with choices" in {
    val text =
      """<%
         [[City:Choice("Zurich", "New York")]]
         [[my city:City]]
         %>{{ my city = "Zurich" => hello world}}""".stripMargin

    resultShouldBe(
      forReview(text, Map("my city" -> "Zurich")),
      """<p class="no-section">hello world</p>"""
    )
  }

  it should "throw an exception if we have an unknown type" in {
    val text =
      """<%
         [[City:My City]]
         %>{{ City = "Zurich" => hello world}}""".stripMargin

    structureAgreement(text) match {
      case Right(_) =>
        fail("should fail")
      case Left(ex) =>
        ex.message shouldBe "error while processing the new variable City. The variable has type My City but it does not exist"
    }
  }

  it should "allow a value from the specified choices" in {
    val text =
      """
         [[Options:Choice("one", "two", "three")]]
         [[option:Options]]
      """.stripMargin

    executeTemplate(text, Map("option" -> "two")) match {
      case Right(executionResult) =>
        executionResult
          .getVariableValue[OpenlawString](VariableName("option"))
          .right
          .value
          .value
          .underlying shouldBe "two"
      case Left(ex) =>
        fail(ex)
    }
  }

  it should "disallow a value not from the specified choices" in {
    val text =
      """
         [[Options:Choice("one", "two", "three")]]
         [[option:Options]]
      """.stripMargin

    val result = structureAgreement(text, Map("option" -> "four"))
    result.left.value.message shouldBe "the value four is not part of the type Options"
  }

  it should "allow specifying values from a structure" in {
    val text =
      """
         [[Name:Structure(
         first: Text;
         last: Text
         )]]
         [[name1:Name]]
      """.stripMargin

    executeTemplate(text) match {
      case Right(executionResult) =>
        val structureType = executionResult
          .findVariableType(VariableTypeDefinition("Name"))
          .getOrElse(NumberType)
        structureType === NumberType shouldBe false
        val newExecutionResult = executeTemplate(
          text,
          Map(
            "name1" -> structureType
              .internalFormat(
                OpenlawMap(
                  Map(
                    VariableName("first") -> OpenlawString("John"),
                    VariableName("last") -> OpenlawString("Doe")
                  )
                )
              )
              .right
              .value
          )
        ).right.value

        service
          .parseExpression("name1.first")
          .flatMap(_.evaluate(newExecutionResult))
          .right
          .value
          .value
          .toString shouldBe "John"
      case Left(ex) =>
        fail(ex)
    }
  }

  it should "use property as an expression in an alias" in {
    val text =
      """
         [[address:Address]]
         [[@My Country = address.country]]
         [[@My Id = address.placeId]]
      """.stripMargin

    executeTemplate(
      text,
      Map(
        "address" -> AddressType
          .internalFormat(
            Address(
              placeId = "placeId",
              streetName = "streetName",
              streetNumber = "streetNumber",
              city = "city",
              state = "state ",
              country = "Country",
              zipCode = "zipCode",
              formattedAddress = "formattedAddress"
            )
          )
          .right
          .value
      )
    ) match {
      case Right(executionResult) =>
        val result = executionResult
          .getAlias("My Id")
          .flatMap(_.evaluate(executionResult).right.value)
        result.value.toString shouldBe "placeId"
      case Left(ex) => fail(ex)
    }
  }

  it should "redefine the variable from Text to YesNo " in {
    val text =
      """
         [[My Conditional]]
         {{My Conditional "this is a question" => hello }}
      """.stripMargin

    executeTemplate(text, Map("My Conditional" -> "true")) match {
      case Success(executionResult) =>
        val variableDefinition = executionResult.getVariables.head

        variableDefinition.name.name shouldBe "My Conditional"
        variableDefinition.variableTypeDefinition.map(_.name) shouldBe Some(
          YesNoType.name
        )
        variableDefinition.description shouldBe Some("this is a question")
      case Failure(ex, message) => fail(message, ex)
    }
  }

  it should "redefine the variable from Text to YesNo even if it is nested" in {
    val text =
      """
         <%
         ==Test==
         [[My Conditional:YesNo "this is a question"]]
         [[BV]]
         %>
         {{My Conditional && BV =>
         {{My Conditional => hello }}
         |{{BV "this is a question" => world }}
         }}
      """.stripMargin

    executeTemplate(text, Map("BV" -> "true", "My Conditional" -> "true")) match {
      case Success(executionResult) =>
        val variableDefinition = executionResult.getVariables
          .filter(_.name.name === "My Conditional")
          .head

        variableDefinition.name.name shouldBe "My Conditional"
        variableDefinition.variableTypeDefinition.map(_.name) shouldBe Some(
          YesNoType.name
        )
        variableDefinition.description shouldBe Some("this is a question")
      case Failure(ex, message) => fail(message, ex)
    }
  }

  it should "not see code block elements as executed" in {
    val text =
      """
        |<%
        |==Effective Date==
        |[[Effective Date: Date]]
        |
        |==Company Name and Address==
        |[[Company Name]]
        |[[Company Address:Address]]
        |[[Corporation:YesNo]]
        |[[LLC:YesNo]]
        |[[PBC:YesNo]]
        |[[State of Incorporation]]
        |
        |==Company Signatory==
        |[[Company Signatory First Name]]
        |[[Company Signatory Last Name]]
        |[[Company Signatory Position]]
        |
        |==Employee Information==
        |[[Employee First Name]]
        |[[Employee Last Name]]
        |[[Employee Address:Address]]
        |[[Recipient Address:EthAddress]]
        |
        |==Employee Position==
        |[[Employee Position]]
        |[[Employee Responsibilities]]
        |[[Position of Supervisor]]
        |
        |==Employee Documents==
        |[[Additional Agreements:YesNo]]
        |[[Confidentiality Agreement]]
        |[[Dispute Resolution]]
        |
        |==Restricted Stock Grant==
        |[[Stock Award:YesNo]]
        |[[Shares of Common Stock:Number]]
        |[[Grant Price]]
        |[[Board Action]]
        |
        |[[Governing Law]]
        |%>
        |
        |{{Additional Agreements "Will the employee be signing additional agreements?" =>
        |{{Confidentiality Agreement "A Confidentiality and Invention Assignment Agreement?" => }}
        |{{Dispute Resolution "An Alternative Dispute Resolution Agreement?" => }}
        |}}
        |
        |{{Corporation "Is the company a corporation?" => [[State of Incorporation]]}}
        |{{LLC "An LLC?" [[State of Incorporation]]}}
        |{{PBC "A Public Benefit Corporation?" [[State of Incorporation]]}}
        |
        |{{Stock Award "A Restricted Stock Grant?" => {{Board Action "Do you want to execute a unanimous action of the board?" => }}}}
      """.stripMargin

    executeTemplate(text, Map.empty) match {
      case Right(executionResult) =>
        executionResult.getAllExecutedVariables.map({
          case (_, name) => name.name
        }) shouldBe Seq(
          "Additional Agreements",
          "Corporation",
          "LLC",
          "PBC",
          "Stock Award"
        )

        executionResult.getVariable("Dispute Resolution") match {
          case Some(variable) =>
            variable.variableTypeDefinition.map(_.name) shouldBe Some(
              YesNoType.name
            )
            variable.description shouldBe Some(
              "An Alternative Dispute Resolution Agreement?"
            )
          case None =>
            fail("Dispute Resolution not found!")
        }
      case Failure(ex, message) => fail(message, ex)
    }
  }

  it should "see sections as non executed" in {
    val text =
      """
        |<%
        |==Conditional with Variables==
        |[[Conditional]]
        |[[Variable A]]
        |[[Variable B]]
        |%>
        |{{Conditional "Do you want to see the variables?" => The value of Variable A is [[Variable A]]. The value of Variable B is [[Variable B]].}}
      """.stripMargin

    executeTemplate(text) match {
      case Success(executionResult) =>
        executionResult.getAllExecutedVariables.map({
          case (_, name) => name.name
        }) shouldBe Seq("Conditional")
      case Failure(ex, message) =>
        fail(message, ex)
    }
  }

  it should "parse [ and ] as characters if it is not the right variable format" in {
    val text = "[this is some text]"

    resultShouldBe(
      forReview(text),
      """<p class="no-section">[this is some text]</p>"""
    )
  }

  it should "be able to define a header in the template" in {
    val text =
      """#########
         show title:false;
         template:test me;
         some value:here;
         description: multi
lines value
here;
         ###########################
         hello world""".stripMargin

    val Right(template) = compiledAgreement(text)

    resultShouldBe(forReview(text), """<p class="no-section">hello world</p>""")

    val actualHeader = template.header

    actualHeader.values("template") shouldBe "test me"
    actualHeader.values("some value") shouldBe "here"
    actualHeader.values("show title") shouldBe "false"
    actualHeader.values("description") shouldBe
      """multi
lines value
here""".stripMargin

    actualHeader.values.size shouldBe 4

    actualHeader.shouldShowTitle shouldBe false
  }

  it should "be able to show the title" in {
    val text =
      """#########
         show title:true;
         ###########################
         """.stripMargin

    val Right(template) = compiledAgreement(text)

    val actualHeader = template.header
    actualHeader.values("show title") shouldBe "true"
    actualHeader.values.size shouldBe 1

    actualHeader.shouldShowTitle shouldBe true
  }

  it should "define show title as false by default" in {
    val text =
      """""".stripMargin

    val Right(template) = compiledAgreement(text)

    val actualHeader = template.header
    actualHeader.shouldShowTitle shouldBe false
  }

  it should "allow specifying an external call" in {
    val inputStructureText =
      """
         |[[FakeServiceInput:Structure(
         |param1: Text;
         |param2: Text)]]
         |[[input:FakeServiceInput]]
      """.stripMargin

    val inputStructureType = executeTemplate(inputStructureText) match {
      case Success(executionResult) =>
        executionResult.findVariableType(
          VariableTypeDefinition("FakeServiceInput")
        ) match {
          case Some(structureType: DefinedStructureType) =>
            structureType.structure
          case Some(variableType) =>
            fail(s"invalid variable type ${variableType.thisType}")
          case None => fail("structure type is not the right type")
        }
      case Failure(ex, message) =>
        fail(message, ex)
    }

    val outputStructureText =
      """
        |[[FakeServiceOutput:Structure(
        |result: Text)]]
        |[[output:FakeServiceOutput]]
      """.stripMargin

    val outputStructureType = executeTemplate(outputStructureText) match {
      case Success(executionResult) =>
        executionResult.findVariableType(
          VariableTypeDefinition("FakeServiceOutput")
        ) match {
          case Some(structureType: DefinedStructureType) =>
            structureType.structure
          case Some(variableType) =>
            fail(s"invalid variable type ${variableType.thisType}")
          case None => fail("structure type is not the right type")
        }
      case Failure(ex, m) =>
        fail(m, ex)
    }

    val input =
      """
         |[[var1:Text]]
         |[[var2:Text]]
         |[[myExternalCall:ExternalCall(
         |serviceName: "FakeServiceInput";
         |parameters:
         | param1 -> var1,
         | param2 -> var2;
         |startDate: '2018-12-12 00:00:00';
         |endDate: '2048-12-12 00:00:00';
         |repeatEvery: '1 hour 30 minutes')]]
         |[[myExternalCall]]
      """.stripMargin

    executeTemplate(
      input,
      Map("param1" -> "5", "param2" -> "5"),
      Map.empty,
      Map(
        ServiceName("FakeServiceInput") -> IntegratedServiceDefinition(
          inputStructureType,
          outputStructureType
        )
      )
    ) match {
      case Right(executionResult) =>
        val externalCallType = executionResult.findVariableType(
          VariableTypeDefinition("ExternalCall")
        ) match {
          case Some(variableType) => variableType
          case None               => fail("structure type is not the right type")
        }
        externalCallType shouldBe ExternalCallType
        val allActions = executionResult.allActions.getOrThrow()
        allActions.size shouldBe 1

        val call = executionResult
          .getVariableValues[ExternalCall](ExternalCallType)
          .getOrThrow()
          .head
        call.serviceName
          .asInstanceOf[StringConstant]
          .value shouldBe "FakeServiceInput"
        call.parameters.map(_.toString) shouldBe List(
          "(param1,var1)",
          "(param2,var2)"
        )
        call.startDate.map(_.toString) shouldBe Some("\"2018-12-12 00:00:00\"")
        call.endDate.map(_.toString) shouldBe Some("\"2048-12-12 00:00:00\"")
        call.every.map(_.toString) shouldBe Some("\"1 hour 30 minutes\"")
      case Left(ex) =>
        fail(ex.message, ex)
    }
  }

  it should "provide the missing input fields for identity type" in {
    val text =
      """
        |[[Signatory: Identity]]
      """.stripMargin

    executeTemplate(text) match {
      case Success(executionResult) =>
        val Success(validationResult) =
          executionResult.toSerializable.validateExecution
        validationResult.missingIdentities shouldBe List(
          VariableName("Signatory")
        )
        validationResult.missingInputs shouldBe List(VariableName("Signatory"))
        executionResult.toSerializable.allMissingInput shouldBe Success(
          List(VariableName("Signatory"))
        )
      case Failure(ex, message) =>
        fail(message, ex)
    }
  }

  it should "check if serviceName is valid for external signature type" in {
    val text =
      """
        |[[Signatory: ExternalSignature(serviceName:"")]]
        |Test simple template
      """.stripMargin

    executeTemplate(text) match {
      case Right(_) =>
        fail(
          "Empty 'serviceName' name value shouldn't be allowed in the template execution"
        )
      case Left(ex) =>
        ex.getMessage shouldBe "Invalid 'serviceName' property for ExternalSignature"
    }
  }

  it should "find the missing input field 'identity' for external signature type" in {
    val text =
      """
        |[[Signatory: ExternalSignature(serviceName:"MyService")]]
        |Test simple template
      """.stripMargin

    executeTemplate(
      text,
      Map.empty,
      Map.empty,
      Map(
        ServiceName("MyService") -> IntegratedServiceDefinition.signatureDefinition
      )
    ) match {
      case Right(executionResult) =>
        val Success(validationResult) = executionResult.validateExecution
        validationResult.missingIdentities shouldBe Seq(
          VariableName("Signatory")
        )
        validationResult.missingInputs shouldBe Seq(VariableName("Signatory"))
        validationResult.validationExpressionErrors shouldBe Seq()
        executionResult.allMissingInput shouldBe Right(
          Seq(VariableName("Signatory"))
        )
      case Failure(ex, message) =>
        fail(message, ex)
    }
  }

  it should "create a new variable type ExternalStorage for Dropbox with a docx file path" in {
    val text =
      """
        |[[Dropbox Storage: ExternalStorage(serviceName:"Dropbox"; docx:"/openlaw/files/Test.doc")]]
        |Test simple template
      """.stripMargin

    executeTemplate(
      text,
      Map.empty,
      Map.empty,
      Map(
        ServiceName("Dropbox") -> IntegratedServiceDefinition.storageDefinition
      )
    ) match {
      case Right(executionResult) =>
        val Success(validationResult) = executionResult.validateExecution
        validationResult.validationExpressionErrors shouldBe Seq()
        val storage = executionResult
          .getVariableValues[ExternalStorage](ExternalStorageType)
          .getOrThrow()
          .head
        storage.serviceName
          .asInstanceOf[StringConstant]
          .value shouldBe "Dropbox"
        storage.filePath
          .asInstanceOf[TemplatePath]
          .path shouldBe List("/openlaw/files/Test.doc")
      case Failure(ex, message) =>
        fail(message, ex)
    }
  }

  it should "not create a new variable type ExternalStorage when arguments are missing" in {
    val textMissingFile =
      """
        |[[Dropbox Storage: ExternalStorage(serviceName:"Dropbox")]]
        |Test simple template
      """.stripMargin

    executeTemplate(
      textMissingFile,
      Map.empty,
      Map.empty,
      Map(
        ServiceName("Dropbox") -> IntegratedServiceDefinition.storageDefinition
      )
    ) match {
      case Right(executionResult) =>
        fail("Variable should not be created when arguments are missing")
      case Failure(ex, message) =>
        message shouldBe "parameter ExternalStorage not found. available parameters: serviceName"
    }

  }

  it should "not create a new variable type ExternalStorage if file type is not supported" in {
    val textMissingFile =
      """
        |[[Dropbox Storage: ExternalStorage(serviceName:"Dropbox"; txt: "/storage/files/myFile.txt")]]
        |Test simple template
      """.stripMargin

    executeTemplate(
      textMissingFile,
      Map.empty,
      Map.empty,
      Map(
        ServiceName("Dropbox") -> IntegratedServiceDefinition.storageDefinition
      )
    ) match {
      case Right(executionResult) =>
        fail("Variable should not be created when arguments are missing")
      case Failure(ex, message) =>
        message shouldBe "parameter ExternalStorage not found. available parameters: serviceName,txt"
    }
  }

  it should "create new ExternalStorage variable for each supported file" in {
    val textMissingFile =
      """
        |[[PDF Storage: ExternalStorage(serviceName:"Dropbox"; pdf: "/storage/files/myFile.pdf")]]
        |[[DOC Storage: ExternalStorage(serviceName:"Dropbox"; doc: "/storage/files/myFile.doc")]]
        |[[DOCX Storage: ExternalStorage(serviceName:"Dropbox"; docx: "/storage/files/myFile.docx")]]
        |[[RTF Storage: ExternalStorage(serviceName:"Dropbox"; rtf: "/storage/files/myFile.rtf")]]
        |[[ODT Storage: ExternalStorage(serviceName:"Dropbox"; odt: "/storage/files/myFile.odt")]]
        |Test simple template
      """.stripMargin

    executeTemplate(
      textMissingFile,
      Map.empty,
      Map.empty,
      Map(
        ServiceName("Dropbox") -> IntegratedServiceDefinition.storageDefinition
      )
    ) match {
      case Right(executionResult) =>
        val Success(validationResult) = executionResult.validateExecution
        validationResult.validationExpressionErrors shouldBe Seq()
        val storages = executionResult
          .getVariableValues[ExternalStorage](ExternalStorageType)
          .getOrThrow()
        storages.flatMap(s => s.filePath.path) shouldBe List(
          "/storage/files/myFile.pdf",
          "/storage/files/myFile.doc",
          "/storage/files/myFile.docx",
          "/storage/files/myFile.rtf",
          "/storage/files/myFile.odt"
        )

      case Failure(ex, message) =>
        fail(message, ex)
    }
  }
}
