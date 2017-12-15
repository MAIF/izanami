package sbtditaa

import java.io.File

import org.stathissideris.ascii2image.core.ConversionOptions
import org.stathissideris.ascii2image.text.TextGrid
import org.stathissideris.ascii2image.graphics.{BitmapRenderer, Diagram, DiagramText}
import java.awt.image.RenderedImage
import java.nio.file.Files
import javax.imageio.ImageIO

import scala.io.Source

object Ditaa {

  def generateDiagrams(source: File, dest: File) : File = {
    getFileTree(source).foreach { f =>
      generateOneDiagram(f, dest)
    }
    dest
  }

  import scala.collection.JavaConverters._

  def getFileTree(f: File): Seq[File] = {
    java.nio.file.Files.walk(f.toPath).iterator().asScala.filter(Files.isRegularFile(_)).toSeq.map(_.toFile)
  }

  def generateOneDiagram(source: File, dest: File) : File = {
    val options = new ConversionOptions()
    val grid = new TextGrid()
    options.renderingOptions.setAntialias(false)
    options.renderingOptions.setDropShadows(false)
    options.processingOptions.setPerformSeparationOfCommonEdges(false)
    options.renderingOptions.setScale(1.0f)
    options.processingOptions.setAllCornersAreRound(false)
    val text = Source.fromFile(source).getLines.mkString("")
    grid.initialiseWithText(text, options.processingOptions)
    val diagram = new Diagram(grid, options)
    val image: RenderedImage = BitmapRenderer.renderToImage(diagram, options.renderingOptions)

    ImageIO.write(image, "png", new File(dest, source.getName.replace(".ditaa", ".png")))
    dest
  }

}
