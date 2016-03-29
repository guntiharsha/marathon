package mesosphere.marathon.core.volume.impl

import com.wix.accord._
import com.wix.accord.combinators.{ Fail, NilValidator }
import com.wix.accord.dsl._
import com.wix.accord.Validator
import mesosphere.marathon.core.volume._
import mesosphere.marathon.state._
import org.apache.mesos.Protos.{ CommandInfo, ContainerInfo, Volume => MesosVolume, Environment }
import scala.collection.JavaConverters._
import scala.reflect.ClassTag

protected trait PersistentVolumeProvider extends VolumeProvider[PersistentVolume] {
  /**
    * don't invoke validator on v because that's circular, just check the additional
    * things that we need for agent local volumes.
    * see implicit validator in the PersistentVolume class for reference.
    */
  val validPersistentVolume: Validator[PersistentVolume]

  /** convenience validator that type-checks for persistent volume */
  val validation = new Validator[Volume] {
    val notPersistentVolume = new Fail[Volume]("is not a persistent volume")
    override def apply(v: Volume): Result = v match {
      case pv: PersistentVolume => validate(pv)(validPersistentVolume)
      case _                    => validate(v)(notPersistentVolume)
    }
  }

  /**
    * @return true if volume has a provider name that matches ours exactly
    */
  def accepts(volume: PersistentVolume): Boolean = {
    volume.persistent.providerName.isDefined && volume.persistent.providerName.get == name
  }

  override def apply(container: Option[Container]): Iterable[PersistentVolume] =
    container.fold(Seq.empty[PersistentVolume]) {
      _.volumes.collect{ case vol: PersistentVolume if accepts(vol) => vol }
    }
}

protected abstract class ContextUpdateHelper[V <: Volume: ClassTag] extends ContextUpdate {

  def accepts(v: V): Boolean

  override protected def updated[C <: BuilderContext](context: C, v: Volume): Option[C] = {
    v match {
      case vol: V if accepts(vol) => {
        context match {
          case cc: ContainerContext => updatedContainer(cc, vol).map(_.asInstanceOf[C])
          case cc: CommandContext   => updatedCommand(cc, vol).map(_.asInstanceOf[C])
        }
      }
      case _ => None
    }
  }
  def updatedContainer(cc: ContainerContext, vol: V): Option[ContainerContext] = None
  def updatedCommand(cc: CommandContext, vol: V): Option[CommandContext] = None
}

/**
  * DVDIProvider (Docker Volume Driver Interface provider) handles persistent volumes allocated
  * by a specific docker volume driver plugin. This works for both docker and mesos containerizers,
  * albeit with some limitations:
  *   - only a single volume driver per container is allowed when using the docker containerizer
  *   - docker containerizer requires that referenced volumes be created prior to application launch
  *   - mesos containerizer only supports volumes mounted in RW mode
  */
protected case object DVDIProvider extends ContextUpdateHelper[PersistentVolume] with PersistentVolumeProvider {

  val name = "dvdi"

  val optionDriver = name + "/driverName"
  val optionIOPS = name + "/iops"
  val optionType = name + "/volumeType"

  val validOptions: Validator[Map[String, String]] = validator[Map[String, String]] { opt =>
    opt.get(optionDriver) as "driverName option" is notEmpty
    // TODO(jdef) stronger validation for contents of driver name
    opt.get(optionDriver).each as "driverName option" is notEmpty
    // TODO(jdef) validate contents of iops and volume type options
  }

  val validPersistentVolume = validator[PersistentVolume] { v =>
    v.persistent.name is notEmpty
    v.persistent.name.each is notEmpty
    v.persistent.providerName is notEmpty
    v.persistent.providerName.each is notEmpty
    v.persistent.providerName.each is equalTo(name) // sanity check
    v.persistent.options is notEmpty
    v.persistent.options.each is valid(validOptions)
  }

  def nameOf(vol: PersistentVolumeInfo): Option[String] = {
    if (vol.providerName.isDefined && vol.name.isDefined) {
      Some(vol.providerName.get + "::" + vol.name.get)
    }
    else None
  }

  // group-level validation for DVDI volumes: the same volume name may only be referenced by a single
  // task instance across the entire cluster.
  val groupValidation: Validator[Group] = new Validator[Group] {
    override def apply(g: Group): Result = {
      val groupViolations = g.apps.flatMap { app =>
        val nameCounts = volumeNameCounts(app)
        val internalNameViolations = {
          nameCounts.filter(_._2 > 1).map{ e =>
            RuleViolation(app.id, s"Requested volume ${e._1} is declared more than once within app ${app.id}", None)
          }
        }
        val instancesViolation: Option[RuleViolation] =
          if (app.instances > 1) Some(RuleViolation(app.id,
            s"Number of instances is limited to 1 when declaring external volumes in app ${app.id}", None))
          else None
        val ruleViolations = DVDIProvider.this.apply(app.container).toSeq.flatMap{ vol =>
          val name = nameOf(vol.persistent)
          if (name.isDefined) {
            for {
              otherApp <- g.transitiveApps.toList
              if otherApp != app.id // do not compare to self
              otherVol <- DVDIProvider.this.apply(otherApp.container)
              otherName <- nameOf(otherVol.persistent)
              if name == otherName
            } yield RuleViolation(app.id,
              s"Requested volume $name conflicts with a volume in app ${otherApp.id}", None)
          }
          else None
        }
        if (internalNameViolations.isEmpty && ruleViolations.isEmpty && instancesViolation.isEmpty) None
        else Some(GroupViolation(app, "app contains conflicting volumes", None,
          internalNameViolations.toSet ++ instancesViolation.toSet ++ ruleViolations.toSet))
      }
      if (groupViolations.isEmpty) Success
      else Failure(groupViolations.toSet)
    }
  }

  def driversInUse(ct: Container): Set[String] =
    DVDIProvider.this.apply(Some(ct)).filter(!_.persistent.options.isEmpty).flatMap{ pv =>
      pv.persistent.options.get.get(optionDriver)
    }.foldLeft(Set.empty[String])(_ + _)

  /** @return a count of volume references-by-name within an app spec */
  def volumeNameCounts(app: AppDefinition): Map[String, Int] =
    DVDIProvider.this.apply(app.container).flatMap{ pv => nameOf(pv.persistent) }.
      groupBy(identity).mapValues(_.size)

  /** Only allow a single docker volume driver to be specified w/ the docker containerizer. */
  val containerValidation: Validator[Container] = validator[Container] { ct =>
    (ct.`type` is equalTo(ContainerInfo.Type.MESOS)) or (
      (ct.`type` is equalTo(ContainerInfo.Type.DOCKER)) and (driversInUse(ct).size should be == 1))
  }

  /** non-agent-local PersistentVolumes can be serialized into a Mesos Protobuf */
  def toMesosVolume(volume: PersistentVolume): MesosVolume =
    MesosVolume.newBuilder
      .setContainerPath(volume.containerPath)
      .setHostPath(volume.persistent.name.get) // validation should protect us from crashing here since name is req'd
      .setMode(volume.mode)
      .build

  override def updatedContainer(cc: ContainerContext, pv: PersistentVolume): Option[ContainerContext] = {
    // special behavior for docker vs. mesos containers
    // - docker containerizer: serialize volumes into mesos proto
    // - docker containerizer: specify "volumeDriver" for the container
    val ci = cc.ci // TODO(jdef) clone?
    if (ci.getType == ContainerInfo.Type.DOCKER && ci.hasDocker) {
      val driverName = pv.persistent.options.get(optionDriver)
      if (ci.getDocker.getVolumeDriver != driverName) {
        ci.setDocker(ci.getDocker.toBuilder.setVolumeDriver(driverName).build)
      }
      Some(ContainerContext(ci.addVolumes(toMesosVolume(pv))))
    }
    None
  }

  override def updatedCommand(cc: CommandContext, pv: PersistentVolume): Option[CommandContext] = {
    // special behavior for docker vs. mesos containers
    // - mesos containerizer: serialize volumes into envvar sets
    val (ct, ci) = (cc.ct, cc.ci) // TODO(jdef) clone ci?
    if (ct == ContainerInfo.Type.MESOS) {
      val env = if (ci.hasEnvironment) ci.getEnvironment.toBuilder else Environment.newBuilder
      val toAdd = volumeToEnv(pv, env.getVariablesList.asScala)
      env.addAllVariables(toAdd.asJava)
      Some(CommandContext(ct, ci.setEnvironment(env.build)))
    }
    None
  }

  val dvdiVolumeName = "DVDI_VOLUME_NAME"
  val dvdiVolumeDriver = "DVDI_VOLUME_DRIVER"
  val dvdiVolumeOpts = "DVDI_VOLUME_OPTS"

  def volumeToEnv(v: PersistentVolume, i: Iterable[Environment.Variable]): Seq[Environment.Variable] = {
    val offset = i.filter(_.getName.startsWith(dvdiVolumeName)).map{ s =>
      val ss = s.getName.substring(dvdiVolumeName.size)
      if (ss.length > 0) ss.toInt else 0
    }.foldLeft(-1)((z, i) => if (i > z) i else z)
    val suffix = if (offset >= 0) (offset + 1).toString else ""

    def newVar(name: String, value: String): Environment.Variable =
      Environment.Variable.newBuilder.setName(name).setValue(value).build

    Seq(
      newVar(dvdiVolumeName + suffix, v.persistent.name.get),
      newVar(dvdiVolumeDriver + suffix, v.persistent.options.get(optionDriver))
    // TODO(jdef) support other options here
    )
  }
}

/**
  * DockerHostVolumeProvider handles Docker volumes that a user would like to mount at
  * predetermined host and container paths. Docker host volumes are not intended to be used
  * with "non-local" docker volume drivers. If you want to use a docker volume driver then
  * use a PersistentVolume instead.
  */
protected case object DockerHostVolumeProvider
    extends ContextUpdateHelper[DockerVolume]
    with VolumeProvider[DockerVolume] {
  val name = "docker" // only because we should have a non-empty name

  /** no special case validation here, it's handled elsewhere */
  val validation: Validator[Volume] = new NilValidator[Volume]

  // no provider-specific rules at the container level
  val containerValidation: Validator[Container] = new NilValidator[Container]

  // no provider-specific rules at the group level
  val groupValidation: Validator[Group] = new NilValidator[Group]

  /** DockerVolumes can be serialized into a Mesos Protobuf */
  def toMesosVolume(volume: DockerVolume): MesosVolume =
    MesosVolume.newBuilder
      .setContainerPath(volume.containerPath)
      .setHostPath(volume.hostPath)
      .setMode(volume.mode)
      .build

  override def accepts(dv: DockerVolume): Boolean = true

  override def updatedContainer(cc: ContainerContext, dv: DockerVolume): Option[ContainerContext] = {
    var ci = cc.ci // TODO(jdef) clone?
    // TODO(jdef) check that this is a DOCKER container type?
    Some(ContainerContext(ci.addVolumes(toMesosVolume(dv))))
  }

  override def apply(container: Option[Container]): Iterable[DockerVolume] =
    container.fold(Seq.empty[DockerVolume])(_.volumes.collect{ case vol: DockerVolume => vol })
}

/**
  * AgentVolumeProvider handles persistent volumes allocated from agent resources.
  */
protected[volume] case object AgentVolumeProvider extends PersistentVolumeProvider with LocalVolumes {
  import org.apache.mesos.Protos.Volume.Mode
  import mesosphere.marathon.api.v2.Validation._

  /** this is the name of the agent volume provider */
  val name = "agent"

  // no provider-specific rules at the container level
  val containerValidation: Validator[Container] = new NilValidator[Container]

  // no provider-specific rules at the group level
  val groupValidation: Validator[Group] = new NilValidator[Group]

  val validPersistentVolume = validator[PersistentVolume] { v =>
    v.persistent.size is notEmpty
    v.mode is equalTo(Mode.RW)
    //persistent volumes require those CLI parameters provided
    v is configValueSet("mesos_authentication_principal", "mesos_role", "mesos_authentication_secret_file")
  }

  override def accepts(volume: PersistentVolume): Boolean = {
    volume.persistent.providerName.getOrElse(name) == name
  }
}
