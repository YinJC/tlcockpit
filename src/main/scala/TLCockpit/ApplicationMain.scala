// TLCockpit
// Copyright 2017 Norbert Preining
// Licensed according to GPLv3+
//
// Front end for tlmgr

package TLCockpit

import TeXLive.{TLPackage, TlmgrProcess}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}
import scalafx.geometry.HPos
import scalafx.scene.control.Alert.AlertType

// needed see https://github.com/scalafx/scalafx/issues/137
import scalafx.scene.control.TableColumn._
import scalafx.scene.control.Menu._
import scalafx.Includes._
import scalafx.application.{JFXApp, Platform}
import scalafx.application.JFXApp.PrimaryStage
import scalafx.geometry.Insets
import scalafx.scene.Scene
import scalafx.scene.layout._
import scalafx.scene.control._
import scalafx.event.ActionEvent
import scalafx.collections.ObservableBuffer
import scala.sys.process._
import scala.collection.mutable.ArrayBuffer


object ApplicationMain extends JFXApp {

  // necessary action when Window is closed with X or some other operation
  override def stopApp(): Unit = {
    tlmgr.cleanup()
  }
  

  val pkgs = ArrayBuffer[TLPackage]()
  val viewpkgs = ObservableBuffer[TLPackage]()

  val outputfield = new TextArea {
    editable = false
    wrapText = true
  }
  val errorfield = new TextArea {
    editable = false
    wrapText = true
  }

  val update_self_button = new Button {
    text = "Update self"
    disable = true
    onAction = (e: ActionEvent) => callback_update_self()
  }
  val update_all_button = new Button {
    text = "Update all"
    disable = true
    onAction = (e: ActionEvent) => callback_update_all()
  }

  val outerrtabs = new TabPane {
    minWidth = 400
    tabs = Seq(
      new Tab {
        text = "Output"
        closable = false
        content = outputfield
      },
      new Tab {
        text = "Error"
        closable = false
        content = errorfield
      }
    )
  }
  val outerrpane = new TitledPane {
    text = "Debug"
    collapsible = true
    expanded = false
    content = outerrtabs
  }

  val cmdline = new TextField()
  val tlmgr = new TlmgrProcess((s:String) => outputfield.text = s,
    (s:String) => {
      errorfield.text = s
      if (s != "") {
        outerrpane.expanded = true
        val selmod = outerrtabs.selectionModel()
        selmod.select(1)
      }
    })

  def tlmgr_async_command(s: String, f: Array[String] => Unit): Unit = {
    errorfield.text = ""
    outputfield.text = ""
    outerrpane.expanded = false
    val foo = Future { tlmgr.send_command(s) }
    foo onComplete {
      case Success(ret) => f(ret)
      case Failure(t) => println("An ERROR has occurred: " + t.getMessage)
    }
  }

  def update_update_button_state(): Unit = {
    update_all_button.disable = ! pkgs.foldLeft(false)(
      (a,b) => a || (if (b.name.value == "texlive.infra") false else b.lrev.value.toInt > 0 && b.lrev.value.toInt < b.rrev.value.toInt)
    )
    // TODO we should change pkgs to a Map with keys are the names + repository (for multi repo support)
    // and the values are llrev/rrev or some combination
    update_self_button.disable = ! pkgs.foldLeft(false)(
      (a,b) => a || (if (b.name.value == "texlive.infra") b.lrev.value.toInt < b.rrev.value.toInt else false)
    )
  }

  def callback_quit(): Unit = {
    tlmgr.cleanup()
    Platform.exit()
    sys.exit(0)
  }

  def callback_load_update_info(s:String, strs: Array[String]): Unit = {
    if (s == "remote") {
      tlmgr_async_command("info --data name,localrev,remoterev,shortdesc", (s: Array[String]) => {
        val newpkgs = ArrayBuffer[TLPackage]()
        s.map((line:String) => {
          val fields: Array[String] = line.split(",", -1)
          val sd = fields(3)
          val shortdesc = if (sd.isEmpty) "" else sd.substring(1).dropRight(1).replace("""\"""",""""""")
          newpkgs += new TLPackage(fields(0), fields(1), fields(2), shortdesc)
        })
        pkgs.clear()
        newpkgs.map(pkgs += _)
        viewpkgs.clear()
        pkgs.map(viewpkgs += _)
        // check for updates available
        update_update_button_state()
      })
    }
  }

  def callback_load(s: String): Unit = {
    tlmgr_async_command("load " + s, callback_load_update_info(s, _) )
  }

  def callback_run_text(s: String): Unit = {
    tlmgr_async_command(s, (s: Array[String]) => {})
    // tlmgr_command(s)
  }
  def callback_run_cmdline(): Unit = {
    callback_run_text(cmdline.text.value)
  }

  def callback_list_collections(): Unit = {
    tlmgr_async_command("info collections", foo => {
      val foo: Array[String] = Array()
      // println("got result from info collections: ")
      // foo.map(println(_))
      outputfield.text = foo.mkString("\n")
      errorfield.text = tlmgr.errorBuffer.toString
    })
  }

  def not_implemented_info(): Unit = {
    new Alert(AlertType.Warning) {
      initOwner(stage)
      title = "Warning"
      headerText = "This functionality is not implemented by now!"
      contentText = "Sorry for the inconveniences."
    }.showAndWait()
  }

  def callback_run_external(s: String): Unit = {
    val foo = Future { s.!! }
    foo.onComplete {
      case Success(ret) => outputfield.text = ret.mkString("\n")
      case Failure(t) => println("An ERROR has occurred: " + t.getMessage)
    }
  }

  def callback_about(): Unit = {
    new Alert(AlertType.Information) {
      initOwner(stage)
      title = "About TLCockpit"
      headerText = "TLCockpit version 0.2\nLicense: GPL3+"
      contentText = "Brought to you by Norbert\nSources: https://github.com/TeX-Live/tlcockpit"
    }.showAndWait()
  }
  def callback_show_all() : Unit = {
    viewpkgs.clear()
    pkgs.map(viewpkgs += _)
  }
  def callback_show_installed() : Unit = {
    viewpkgs.clear()
    pkgs.map(p => if (p.lrev.value.toInt > 0) viewpkgs += p)
  }
  def callback_show_updates(): Unit = {
    val newpkgs = ArrayBuffer[TLPackage]()
    pkgs.map(p =>
      // TODO we are not dealing with not integer values by now!
      if (p.lrev.value.toInt > 0 && p.lrev.value.toInt < p.rrev.value.toInt) {
        newpkgs += p
      }
    )
    viewpkgs.clear()
    newpkgs.map(viewpkgs += _)
  }
  def callback_update_all() : Unit = {
    tlmgr_async_command("update --all", s => {
      update_update_button_state()
      callback_show_all()
    })
  }
  def callback_update_self() : Unit = {
    // TODO
    // should we restart tlmgr here - it might be necessary!!!
    tlmgr_async_command("update --self", s => {
      update_update_button_state()
      callback_show_all()
    })
  }


  def callback_show_pkg_info(pkg: String): Unit = {
    // val pkginfo = tlmgr_command(s"info $pkg")
    tlmgr_async_command(s"info $pkg", pkginfo => {
      // need to call runLater to go back to main thread!
      Platform.runLater {
        val dialog = new Dialog() {
          initOwner(stage)
          title = s"Package Information for $pkg"
          headerText = s"Package Information for $pkg"
          resizable = true
        }
        dialog.dialogPane().buttonTypes = Seq(ButtonType.OK)
        val grid = new GridPane() {
          hgap = 10
          vgap = 10
          padding = Insets(20)
        }
        var crow = 0
        pkginfo.foreach((line: String) => {
          val keyval = line.split(":", 2).map(_.trim)
          if (keyval.length == 2) {
            val keylabel = new Label(keyval(0))
            val vallabel = new Label(keyval(1))
            vallabel.wrapText = true
            grid.add(keylabel, 0, crow)
            grid.add(vallabel, 1, crow)
            crow += 1
          }
        })
        grid.columnConstraints = Seq(new ColumnConstraints(100, 150, 200), new ColumnConstraints(100, 300, 5000, Priority.Always, new HPos(HPos.Left), true))
        dialog.dialogPane().content = grid
        dialog.width = 500
        dialog.showAndWait()
      }
    })
  }

  var testmode = false
  if (parameters.unnamed.nonEmpty) {
    if (parameters.unnamed.head == "-test" || parameters.unnamed.head == "--test") {
      println("Testing mode enabled, not actually calling tlmgr!")
      testmode = true
    }
  }

  if (!testmode) {
    tlmgr.start_process()

    // wait until we got a prompt
    val foo: Array[String] = tlmgr.get_output_till_prompt()
    // println("current output: ")
    // foo.map(println(_))
  }

  stage = new PrimaryStage {
    title = "TLCockpit"
    scene = new Scene {
      root = {
        val topBox = new MenuBar {
          useSystemMenuBar = true
          menus.add(new Menu("TLCockpit") {
            items = List(
              new MenuItem("Load default (from tlpdb) repository") {
                onAction = (ae: ActionEvent) => callback_load("remote")
              },
              new MenuItem("Load standard net repository") {
                onAction = (ae: ActionEvent) => not_implemented_info()
                disable = true
              },
              new MenuItem("Load other repository ...") {
                onAction = (ae: ActionEvent) => not_implemented_info()
                disable = true
              },
              new SeparatorMenuItem,
              new MenuItem("Exit") {
                onAction = (ae: ActionEvent) => callback_quit()
              }
            )
          })
          menus.add(new Menu("Options") {
            items = List(
              new MenuItem("General ...") { disable = true; onAction = (ae) => not_implemented_info() },
              new MenuItem("Paper ...") { disable = true; onAction = (ae) => not_implemented_info() },
              new MenuItem("Platforms ...") { disable = true; onAction = (ae) => not_implemented_info() },
              new SeparatorMenuItem,
              new CheckMenuItem("Expert options") { disable = true },
              new CheckMenuItem("Enable debugging options") { disable = true },
              new CheckMenuItem("Disable auto-install of new packages") { disable = true },
              new CheckMenuItem("Disable auto-removal of server-deleted packages") { disable = true }
            )
          })
          menus.add(new Menu("Actions") {
            items = List(
              new MenuItem("Update filename database ...") { onAction = (ae) => callback_run_external("mktexlsr") },
              new MenuItem("Rebuild all formats ...") { onAction = (ae) => callback_run_external("fmtutil --sys --all") },
              new MenuItem("Update font map database ...") { onAction = (ae) => callback_run_external("updmap --sys") },
              new MenuItem("Restore packages from backup ...") { disable = true; onAction = (ae) => not_implemented_info() },
              new MenuItem("Handle symlinks in system dirs ...") { disable = true; onAction = (ae) => not_implemented_info() },
              new SeparatorMenuItem,
              new MenuItem("Remove TeX Live ...") { disable = true; onAction = (ae) => not_implemented_info() }
            )
          })
          menus.add(new Menu("Help") {
            items = List(
              new MenuItem("Manual") { disable = true; onAction = (ae) => not_implemented_info() },
              new MenuItem("About") { onAction = (ae) => callback_about() },
            )
          })
        }
        val centerBox = new VBox {
          padding = Insets(10)
          children = List(
            new TitledPane {
              text = "Actions"
              collapsible = false
              content = new VBox {
                spacing = 10
                children = List(
                  new HBox {
                    spacing = 10
                    children = List(
                      cmdline,
                      new Button {
                        text = "Go"
                        onAction = (event: ActionEvent) => callback_run_cmdline()
                      },
                      new Button {
                        text = "Load repository";
                        onAction = (event: ActionEvent) => callback_load("remote")
                      }
                    )
                  },
                  new HBox {
                    spacing = 10
                    children = List(
                      new Button {
                        text = "Show updates"
                        onAction = (e: ActionEvent) => callback_show_updates()
                      },
                      new Button {
                        text = "Show installed"
                        onAction = (e: ActionEvent) => callback_show_installed()
                      },
                      new Button {
                        text = "Show all"
                        onAction = (e: ActionEvent) => callback_show_all()
                      },
                      update_self_button,
                      update_all_button
                    )
                  }
                )
              }
            },
            outerrpane,
            {
              val col1 = new TableColumn[TLPackage, String] {
                text = "Package"
                cellValueFactory = {
                  _.value.name
                }
                prefWidth = 150
              }
              val col2 = new TableColumn[TLPackage, String] {
                text = "Local rev"
                cellValueFactory = {
                  _.value.lrev
                }
                prefWidth = 100
              }
              val col3 = new TableColumn[TLPackage, String] {
                text = "Remote rev"
                cellValueFactory = {
                  _.value.rrev
                }
                prefWidth = 100
              }
              val col4 = new TableColumn[TLPackage, String] {
                text = "Description"
                cellValueFactory = {
                  _.value.shortdesc
                }
                prefWidth = 300
              }
              val table = new TableView[TLPackage](viewpkgs) {
                columns ++= List(col1,col2,col3,col4)
              }
              col4.prefWidth.bind(table.width - col1.width - col2.width - col3.width)
              table.prefHeight = 300
              table.vgrow = Priority.Always
              table.rowFactory = { _ =>
                val row = new TableRow[TLPackage] {}
                val ctm = new ContextMenu(
                  new MenuItem("Info") { onAction = (ae) => callback_show_pkg_info(row.item.value.name.value) },
                  new MenuItem("Install") {
                    onAction = (ae) => callback_run_text("install " + row.item.value.name.value)
                  },
                  new MenuItem("Remove") {
                    onAction = (ae) => callback_run_text("remove " + row.item.value.name.value)
                  },
                  new MenuItem("Update") {
                    onAction = (ae) => callback_run_text("update " + row.item.value.name.value)
                  }
                )
                row.contextMenu = ctm
                row
              }
              table
            },
          )
        }
        /*       val bottomBox = new HBox {
                 padding = Insets(10)
                 spacing = 10
                 children = List(
                   new Button {
                     text = "Quit"
                     onAction = (event: ActionEvent) => callback_quit()
                   }
                 )
               }
       */
        new BorderPane {
          // padding = Insets(20)
          top = topBox
          // left = leftBox
          center = centerBox
          // bottom = bottomBox
        }
      }
    }
  }

  val startalert = new Alert(AlertType.Information)
  startalert.setTitle("Loading package database ...")
  startalert.setContentText("Loading package database, this might take a while. Please wait!")
  startalert.show()

  // load the initial set of packages
  var pkglines: Array[String] = Array()

  tlmgr_async_command("info --only-installed --data name,localrev,shortdesc", (s: Array[String]) => {
    s.map(line => {
      val fields: Array[String] = line.split(",", -1)
      val sd = if (fields(2).isEmpty) "" else fields(2).substring(1).dropRight(1).replace("""\"""",""""""")
      pkgs += new TLPackage(fields(0), fields(1), "0", sd)
    })
    viewpkgs.clear()
    pkgs.map(viewpkgs += _)
    Platform.runLater { startalert.close() }
  })


}  // object ApplicationMain

// vim:set tabstop=2 expandtab : //