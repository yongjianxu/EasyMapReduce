package se.uu.it.easymr

import java.io.File
import java.io.PrintWriter
import java.util.UUID

private[easymr] object EasyFiles {
  
  private val tmpDir = if(System.getenv("EASYMR_TMP") != null) {
    new File(System.getenv("EASYMR_TMP"))
  } else if (System.getenv("TMPDIR") != null) {
    new File(System.getenv("TMPDIR"))
  } else {
    throw new IllegalStateException(
      "Environment variables EASYMR_TMP and TMPDIR are not defined. " +
      "Please define at least one of the two."
    )
  }
    
  private def newTmpFile = new File(tmpDir, "easymr_" + UUID.randomUUID.toString)
  
  def createTmpFile = {
    val file = EasyFiles.newTmpFile
    file.createNewFile
    file
  }
  
  def writeToTmpFile(toWrite: String) = {
    val file = EasyFiles.newTmpFile
    val pw = new PrintWriter(file)
    pw.write(toWrite)
    pw.close
    file
  }
  
}