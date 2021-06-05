/*
 * Copyright 2019 WeBank
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.webank.wedatasphere.linkis.engineconn.acessible.executor.service

import java.util.concurrent.TimeUnit
import com.webank.wedatasphere.linkis.DataWorkCloudApplication
import com.webank.wedatasphere.linkis.common.utils.{Logging, Utils}
import com.webank.wedatasphere.linkis.engineconn.acessible.executor.conf.AccessibleExecutorConfiguration
import com.webank.wedatasphere.linkis.engineconn.acessible.executor.entity.AccessibleExecutor
import com.webank.wedatasphere.linkis.engineconn.acessible.executor.listener.event.{ExecutorCompletedEvent, ExecutorCreateEvent, ExecutorStatusChangedEvent}
import com.webank.wedatasphere.linkis.engineconn.core.EngineConnObject
import com.webank.wedatasphere.linkis.engineconn.core.engineconn.EngineConnManager
import com.webank.wedatasphere.linkis.engineconn.core.executor.ExecutorManager
import com.webank.wedatasphere.linkis.engineconn.core.hook.ShutdownHook
import com.webank.wedatasphere.linkis.engineconn.executor.entity.{Executor, SensibleExecutor}
import com.webank.wedatasphere.linkis.engineconn.executor.listener.ExecutorListenerBusContext
import com.webank.wedatasphere.linkis.engineconn.executor.service.ManagerService
import com.webank.wedatasphere.linkis.manager.common.entity.enumeration.NodeStatus
import com.webank.wedatasphere.linkis.manager.common.protocol.engine.{EngineConnReleaseRequest, EngineSuicideRequest}
import com.webank.wedatasphere.linkis.manager.common.protocol.node.{RequestNodeStatus, ResponseNodeStatus}
import com.webank.wedatasphere.linkis.message.annotation.Receiver
import com.webank.wedatasphere.linkis.message.builder.ServiceMethodContext
import com.webank.wedatasphere.linkis.rpc.Sender

import javax.annotation.PostConstruct
import org.apache.commons.lang.exception.ExceptionUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.event.{ContextClosedEvent, EventListener}
import org.springframework.stereotype.Service


@Service
class DefaultAccessibleService extends AccessibleService with Logging {

  @Autowired
  private var executorHeartbeatService: ExecutorHeartbeatService = _

  private val asyncListenerBusContext = ExecutorListenerBusContext.getExecutorListenerBusContext().getEngineConnAsyncListenerBus

  @Receiver
  override def dealEngineStopRequest(engineSuicideRequest: EngineSuicideRequest, smc: ServiceMethodContext): Unit = {
    // todo check user
    if (DataWorkCloudApplication.getServiceInstance.equals(engineSuicideRequest.getServiceInstance)) {
      stopEngine()
      info(s"engine will suiside now.")
      ShutdownHook.getShutdownHook.notifyStop()
    } else {
      if (null != engineSuicideRequest.getServiceInstance) {
        error(s"Invalid serviceInstance : ${engineSuicideRequest.getServiceInstance.toString}, will not suicide.")
      } else {
        error("Invalid empty serviceInstance.")
      }
    }
  }


  @EventListener
  def executorShutDownHook(event: ContextClosedEvent): Unit = {
    info("executorShutDownHook  start to execute.")
    var executor: Executor = ExecutorManager.getInstance.getReportExecutor
    if (null != executor){
      val sensibleExecutor = executor.asInstanceOf[SensibleExecutor]
      if (NodeStatus.isAvailable(sensibleExecutor.getStatus)) {
        warn("executorShutDownHook  start to close executor...")
        executor.close()
        Utils.tryAndWarn{
          executor.tryShutdown()
          Thread.sleep(2000)
        }
        warn(s"Engine : ${Sender.getThisInstance} with state has stopped successfully.")
      }
    } else {
      executor = SensibleExecutor.getDefaultErrorSensibleExecutor
    }
    executorHeartbeatService.reportHeartBeatMsg(executor)
    info("Reported status shuttingDown to manager.")
  }

  override def stopExecutor: Unit = {
    // todo
  }


  override def pauseExecutor: Unit = {

  }

  override def reStartExecutor: Boolean = {
    true
  }

  /**
    * Service启动后则启动定时任务 空闲释放
    */
  @PostConstruct
  def init(): Unit = {
    val context = EngineConnObject.getEngineCreationContext
    val maxFreeTimeVar = AccessibleExecutorConfiguration.ENGINECONN_MAX_FREE_TIME.getValue(context.getOptions)
    val maxFreeTimeStr = maxFreeTimeVar.toString
    val maxFreeTime = maxFreeTimeVar.toLong
    info("maxFreeTimeMills is " + maxFreeTime)
    Utils.defaultScheduler.scheduleAtFixedRate(new Runnable {
      override def run(): Unit = Utils.tryAndWarn {
        val accessibleExecutor = ExecutorManager.getInstance.getReportExecutor match {
          case executor: AccessibleExecutor => executor
          case executor: Executor =>
            warn(s"Executor(${executor.getId}) is not a AccessibleExecutor, do noting when reached max free time .")
            return
        }
        if (NodeStatus.isCompleted(accessibleExecutor.getStatus)) {
          error(s"${accessibleExecutor.getId} has completed with status ${accessibleExecutor.getStatus}, now stop it.")
          ShutdownHook.getShutdownHook.notifyStop()
        } else if (accessibleExecutor.getStatus == NodeStatus.ShuttingDown) {
          warn(s"${accessibleExecutor.getId} is ShuttingDown...")
          ShutdownHook.getShutdownHook.notifyStop()
        } else if (maxFreeTime > 0 && NodeStatus.Unlock.equals(accessibleExecutor.getStatus) && System.currentTimeMillis - accessibleExecutor.getLastActivityTime > maxFreeTime) {
          warn(s"${accessibleExecutor.getId} has not been used for $maxFreeTimeStr, now try to shutdown it.")
          ShutdownHook.getShutdownHook.notifyStop()
          requestManagerReleaseExecutor(" idle release")
          Utils.defaultScheduler.scheduleWithFixedDelay(new Runnable {
            override def run(): Unit = {
              Utils.tryCatch {
                warn(s"Now exit with code ${ShutdownHook.getShutdownHook.getExitCode()}")
                System.exit(ShutdownHook.getShutdownHook.getExitCode())
              } { t =>
                  error(s"Exit error : ${ExceptionUtils.getRootCauseMessage(t)}.", t)
                  System.exit(-1)
              }
            }
          }, 3000,1000*10, TimeUnit.MILLISECONDS)
        }
      }
    }, 3 * 60 * 1000, AccessibleExecutorConfiguration.ENGINECONN_HEARTBEAT_TIME.getValue.toLong, TimeUnit.MILLISECONDS)
    asyncListenerBusContext.addListener(this)
    /*Utils.defaultScheduler.submit(new Runnable {
      override def run(): Unit = {
        Utils.addShutdownHook(executorShutDownHook())
        info("Succeed to register shutdownHook.")
      }
    })*/
  }

  private def stopEngine(): Unit = {
    Utils.tryAndWarn {
      ExecutorManager.getInstance.getExecutors.foreach(_.tryShutdown())
    }
  }

  /**
    * service 需要加定时任务判断Executor是否空闲很久，然后调用该方法进行释放
    */

  override def requestManagerReleaseExecutor(msg: String): Unit = {
    val engineReleaseRequest = new EngineConnReleaseRequest(Sender.getThisServiceInstance, Utils.getJvmUser, msg, EngineConnManager.getEngineConnManager.getEngineConn.getEngineCreationContext.getTicketId)
    ManagerService.getManagerService.requestReleaseEngineConn(engineReleaseRequest)
  }

  @Receiver
  override def dealRequestNodeStatus(requestNodeStatus: RequestNodeStatus): ResponseNodeStatus = {
    val status = ExecutorManager.getInstance.getReportExecutor match {
      case executor: SensibleExecutor =>
        executor.getStatus
      case _ => NodeStatus.Starting
    }
    val responseNodeStatus = new ResponseNodeStatus
    responseNodeStatus.setNodeStatus(status)
    responseNodeStatus
  }

  override def onExecutorCreated(executorCreateEvent: ExecutorCreateEvent): Unit = {
    info(s"Executor(${executorCreateEvent.executor.getId}) created")
  }

  override def onExecutorCompleted(executorCompletedEvent: ExecutorCompletedEvent): Unit = {
    reportHeartBeatMsg(executorCompletedEvent.executor)
  }

  override def onExecutorStatusChanged(executorStatusChangedEvent: ExecutorStatusChangedEvent): Unit = {
    reportHeartBeatMsg(executorStatusChangedEvent.executor)
  }

  private def reportHeartBeatMsg(executor: Executor): Unit = {
    val reportExecutor = executor match {
      case accessibleExecutor: AccessibleExecutor => accessibleExecutor
      case e: Executor =>
        warn(s"Executor(${e.getId}) is not a AccessibleExecutor, do noting on status changed.")
        return
    }
    executorHeartbeatService.reportHeartBeatMsg(reportExecutor)
  }

}
