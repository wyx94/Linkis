/*
 * Copyright 2019 WeBank
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
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

package com.webank.wedatasphere.linkis.entrance.interceptor.impl

import com.webank.wedatasphere.linkis.entrance.interceptor.EntranceInterceptor
import com.webank.wedatasphere.linkis.entrance.interceptor.exception.CodeCheckException
import com.webank.wedatasphere.linkis.governance.common.entity.job.JobRequest
import com.webank.wedatasphere.linkis.manager.label.utils.LabelUtil

/**
  * created by enjoyyin on 2018/10/22
  * Description:
  * Yòng yú jiǎnchá spark dàimǎ
  * 11/5000
  * Used to check the spark code(用于检查spark代码)
  */
class SparkCodeCheckInterceptor extends EntranceInterceptor {

  override def apply(jobRequest: JobRequest, logAppender: java.lang.StringBuilder): JobRequest = {
    val codeType = LabelUtil.getCodeType(jobRequest.getLabels)
    codeType match {
      case "scala" => val codeBuilder: StringBuilder = new StringBuilder()
        val isAuth = SparkExplain.authPass(jobRequest.getExecutionCode, codeBuilder)
            if (!isAuth) {
              throw CodeCheckException(20050, "spark code check failed")
            }
          case _ =>
        }
    jobRequest
    }
  }
