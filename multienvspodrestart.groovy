def call(Map config = [:]) {

    // Environment configuration from global pipeline
    def environments_info = [
        blue: [NAME: 'BLUE', EKS_NAMESPACE: 'prod', EKS_KUBECONFIG: 'eks_kubeconfig_prod'],
        green: [NAME: 'GREEN', EKS_NAMESPACE: 'stage', EKS_KUBECONFIG: 'eks_kubeconfig_stage']
    ]
    
    def branch_environments = [
        development: [NAME: 'development', EKS_NAMESPACE: 'dev', EKS_KUBECONFIG: 'eks_kubeconfig_dev'],
        staging: [NAME: 'staging', EKS_NAMESPACE: '--KXENVTOGGLE--', EKS_KUBECONFIG: '--KXENVTOGGLE--'],
        qat: [NAME: 'qat', EKS_NAMESPACE: 'qat', EKS_KUBECONFIG: 'eks_kubeconfig_qat'],
        master: [NAME: 'production', EKS_NAMESPACE: '--KXENVTOGGLE--', EKS_KUBECONFIG: '--KXENVTOGGLE--'],
        prodmaster: [NAME: 'production', EKS_NAMESPACE: '--KXENVTOGGLE--', EKS_KUBECONFIG: '--KXENVTOGGLE--']
    ]

    // Get parameters - support both direct config and Jenkins params
    def environment    = config.get("environment", params.NAMESPACE ?: "dev")
    def podName        = config.get("podName", params.DEPLOYMENT_NAME ?: "").trim()
    def waitForReady   = config.get("waitForReady", true)
    def waitTimeout    = config.get("waitTimeout", 60)
    
    // Teams webhook - hardcoded for notifications
    def teamsWebhook   = "https://klearexpress.webhook.office.com/webhookb2/288847ed-b575-4944-8831-c346f30c2078@f657a353-bf95-4d79-8026-d82f54ce9a3b/IncomingWebhook/57f1db28fb374446be410bd2cf5cfeb8/1879c483-94f2-4212-b464-557d0e61f743/V2pUKl-TbZ9850XNUMTHOvcOL6jzuw8B-bHidxTRN4tTw1"
    
    // Resolve namespace and kubeconfig based on environment
    def namespace = ""
    def kubeconfigId = ""
    def clusterName = ""

    if (!environment?.trim()) {
        error "Missing required parameter: environment (dev/staging/qat/prod)"
    }
    if (!podName?.trim()) {
        error "Missing required parameter: podName (provide exact pod name)"
    }

    // Determine namespace and kubeconfig based on environment
    if (environment.toLowerCase() == "dev" || environment.toLowerCase() == "development") {
        namespace = branch_environments.development.EKS_NAMESPACE
        kubeconfigId = branch_environments.development.EKS_KUBECONFIG
        clusterName = "EKS Development"
    } else if (environment.toLowerCase() == "staging" || environment.toLowerCase() == "stage") {
        // For staging, check if it needs toggling
        if (branch_environments.staging.EKS_NAMESPACE == "--KXENVTOGGLE--") {
            // Try to get the current environment binding
            def envBinding = getEnvironmentBinding("staging")
            if (envBinding && environments_info.containsKey(envBinding)) {
                namespace = environments_info[envBinding].EKS_NAMESPACE
                kubeconfigId = environments_info[envBinding].EKS_KUBECONFIG
                clusterName = "EKS ${environments_info[envBinding].NAME}"
            } else {
                // Default to stage if no binding found
                namespace = "stage"
                kubeconfigId = "eks_kubeconfig_stage"
                clusterName = "EKS Staging"
            }
        } else {
            namespace = branch_environments.staging.EKS_NAMESPACE
            kubeconfigId = branch_environments.staging.EKS_KUBECONFIG
            clusterName = "EKS Staging"
        }
    } else if (environment.toLowerCase() == "qat") {
        namespace = branch_environments.qat.EKS_NAMESPACE
        kubeconfigId = branch_environments.qat.EKS_KUBECONFIG
        clusterName = "EKS QAT"
    } else if (environment.toLowerCase() == "prod" || environment.toLowerCase() == "production" || environment.toLowerCase() == "master") {
        // For production, check if it needs toggling
        if (branch_environments.master.EKS_NAMESPACE == "--KXENVTOGGLE--") {
            // Try to get the current environment binding
            def envBinding = getEnvironmentBinding("production")
            if (envBinding && environments_info.containsKey(envBinding)) {
                namespace = environments_info[envBinding].EKS_NAMESPACE
                kubeconfigId = environments_info[envBinding].EKS_KUBECONFIG
                clusterName = "EKS ${environments_info[envBinding].NAME}"
            } else {
                // Default to prod if no binding found
                namespace = "prod"
                kubeconfigId = "eks_kubeconfig_prod"
                clusterName = "EKS Production"
            }
        } else {
            namespace = branch_environments.master.EKS_NAMESPACE
            kubeconfigId = branch_environments.master.EKS_KUBECONFIG
            clusterName = "EKS Production"
        }
    } else {
        error "Invalid environment: ${environment}. Valid values: dev, staging, qat, prod"
    }

    echo "Resolved Configuration:"
    echo "  Environment: ${environment}"
    echo "  Namespace: ${namespace}"
    echo "  Kubeconfig: ${kubeconfigId}"
    echo "  Cluster: ${clusterName}"

    // Determine node selector based on environment
    def nodeSelector = (environment.toLowerCase() == "prod" || environment.toLowerCase() == "production" || environment.toLowerCase() == "master") ? 'karpenter-prod' : 'karpenter'

    def podYaml = """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: go
    image: 035592488042.dkr.ecr.us-west-2.amazonaws.com/jenkins-agents:new-gospin
    command:
    - cat
    tty: true
    volumeMounts:
    - mountPath: /var/lib/jenkins
      name: persistent-storage
  nodeSelector:
    type: ${nodeSelector}
  securityContext:
    seLinuxOptions:
      type: spc_t
  volumes:
  - name: persistent-storage
    persistentVolumeClaim:
      claimName: efs-jenkins-claim
"""

    def newPodName = "N/A"
    def restartSuccess = false
    def startTime = new Date()

    podTemplate(yaml: podYaml) {
        node(POD_LABEL) {
            container('go') {
                try {
                    stage('Prepare & Checkout') {
                        echo "=========================================="
                        echo "Pod Restart Pipeline"
                        echo "=========================================="
                        echo "Environment:  ${environment.toUpperCase()}"
                        echo "Cluster:      ${clusterName}"
                        echo "Namespace:    ${namespace}"
                        echo "Pod Name:     ${podName}"
                        echo "Node Type:    ${nodeSelector}"
                        echo "=========================================="
                        
                        withCredentials([
                            [$class: 'AmazonWebServicesCredentialsBinding', 
                             accessKeyVariable: 'AWS_ACCESS_KEY_ID', 
                             credentialsId: 'AWS_Credentials', 
                             secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']
                        ]) {
                            env.AWS_DEFAULT_REGION = 'us-west-2'
                            sh "aws configure set default.region ${env.AWS_DEFAULT_REGION}"
                        }
                    }

                    stage('Delete Old Pod') {
                        withCredentials([
                            [$class: 'AmazonWebServicesCredentialsBinding', 
                             accessKeyVariable: 'AWS_ACCESS_KEY_ID', 
                             credentialsId: 'AWS_Credentials', 
                             secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']
                        ]) {
                            configFileProvider([configFile(fileId: kubeconfigId, targetLocation: "./${kubeconfigId}")]) {
                                sh """
                                    set -e
                                    export KUBECONFIG=./${kubeconfigId}
                                    
                                    echo "Checking if pod '${podName}' exists in namespace '${namespace}'..."
                                    if ! kubectl get pod ${podName} -n ${namespace} >/dev/null 2>&1; then
                                        echo "ERROR: Pod '${podName}' not found in namespace '${namespace}'"
                                        echo ""
                                        echo "Available pods in namespace '${namespace}':"
                                        kubectl get pods -n ${namespace} -o custom-columns=NAME:.metadata.name,STATUS:.status.phase,AGE:.metadata.creationTimestamp
                                        exit 1
                                    fi

                                    POD_STATUS=\$(kubectl get pod ${podName} -n ${namespace} -o jsonpath='{.status.phase}')
                                    echo "Current pod status: \${POD_STATUS}"
                                    echo ""
                                    
                                    echo "Deleting pod '${podName}'..."
                                    kubectl delete pod ${podName} -n ${namespace} --wait=true
                                    echo "✓ Pod deleted successfully."
                                    echo ""
                                """
                            }
                        }
                    }

                    stage('Create New Pod') {
                        if (waitForReady) {
                            withCredentials([
                                [$class: 'AmazonWebServicesCredentialsBinding', 
                                 accessKeyVariable: 'AWS_ACCESS_KEY_ID', 
                                 credentialsId: 'AWS_Credentials', 
                                 secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']
                            ]) {
                                configFileProvider([configFile(fileId: kubeconfigId, targetLocation: "./${kubeconfigId}")]) {
                                    echo "Waiting up to ${waitTimeout}s for replacement pod to become Running..."
                                    
                                    sh """
                                        export KUBECONFIG=./${kubeconfigId}
                                        
                                        echo "Finding replacement pod..."
                                        DEPLOYMENT_PREFIX=\$(echo "${podName}" | sed 's/-[^-]*-[^-]*\$//')
                                        echo "Looking for new pod with prefix: \${DEPLOYMENT_PREFIX}"
                                        echo ""
                                        
                                        ELAPSED=0
                                        while [ \${ELAPSED} -lt ${waitTimeout} ]; do
                                            NEW_POD=\$(kubectl get pods -n ${namespace} --sort-by=.metadata.creationTimestamp -o name | grep "pod/\${DEPLOYMENT_PREFIX}-" | grep -v "${podName}" | tail -n 1 | cut -d'/' -f2 || true)
                                            
                                            if [ -n "\${NEW_POD}" ]; then
                                                STATUS=\$(kubectl get pod "\${NEW_POD}" -n ${namespace} -o jsonpath='{.status.phase}' 2>/dev/null || echo "Unknown")
                                                echo "Replacement pod: \${NEW_POD} | Status: \${STATUS}"
                                                
                                                if [ "\${STATUS}" = "Running" ]; then
                                                    READY=\$(kubectl get pod "\${NEW_POD}" -n ${namespace} -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || echo "False")
                                                    
                                                    if [ "\${READY}" = "True" ]; then
                                                        echo ""
                                                        echo "======================================"
                                                        echo "✓ Replacement pod is Running and Ready!"
                                                        echo "  Old pod: ${podName}"
                                                        echo "  New pod: \${NEW_POD}"
                                                        echo "======================================"
                                                        
                                                        # Write new pod name to file for Jenkins to read
                                                        echo "\${NEW_POD}" > new_pod_name.txt
                                                        exit 0
                                                    fi
                                                fi
                                            else
                                                echo "Waiting for replacement pod to be created..."
                                            fi
                                            
                                            sleep 3
                                            ELAPSED=\$((ELAPSED + 3))
                                        done
                                        
                                        echo ""
                                        echo "======================================"
                                        echo "⚠ WARNING: Timeout waiting for replacement pod"
                                        echo "The pod was deleted but replacement not ready within ${waitTimeout}s"
                                        echo "======================================"
                                        exit 1
                                    """
                                    
                                    // Read the new pod name from file
                                    if (fileExists('new_pod_name.txt')) {
                                        newPodName = readFile('new_pod_name.txt').trim()
                                        echo "New pod created: ${newPodName}"
                                    }
                                }
                            }
                        } else {
                            echo "Skipping wait for new pod (waitForReady=false)"
                        }
                    }
                    
                    restartSuccess = true

                } catch (Exception e) {
                    restartSuccess = false
                    currentBuild.result = 'FAILURE'
                    
                    // Send failure notification
                    def endTime = new Date()
                    def duration = groovy.time.TimeCategory.minus(endTime, startTime)
                    sendTeamsNotification(
                        teamsWebhook,
                        false,
                        environment,
                        clusterName,
                        namespace,
                        podName,
                        newPodName,
                        duration.toString(),
                        e.getMessage()
                    )
                    
                    throw e
                    
                } finally {
                    stage('Complete') {
                        if (restartSuccess) {
                            def endTime = new Date()
                            def duration = groovy.time.TimeCategory.minus(endTime, startTime)
                            
                            echo "=========================================="
                            echo "✓ Pod Restart Complete!"
                            echo "  Environment: ${environment.toUpperCase()}"
                            echo "  Cluster: ${clusterName}"
                            echo "  Namespace: ${namespace}"
                            echo "  Old Pod: ${podName}"
                            echo "  New Pod: ${newPodName}"
                            echo "  Duration: ${duration}"
                            echo "=========================================="
                            
                            // Send success notification
                            sendTeamsNotification(
                                teamsWebhook,
                                true,
                                environment,
                                clusterName,
                                namespace,
                                podName,
                                newPodName,
                                duration.toString(),
                                null
                            )
                        } else {
                            echo "=========================================="
                            echo "✗ Pod Restart Failed!"
                            echo "  Environment: ${environment.toUpperCase()}"
                            echo "  Cluster: ${clusterName}"
                            echo "  Namespace: ${namespace}"
                            echo "  Pod: ${podName}"
                            echo "=========================================="
                        }
                    }
                }
            }
        }
    }
}

// Helper function to send Teams notifications
def sendTeamsNotification(webhookUrl, success, environment, cluster, namespace, oldPod, newPod, duration, errorMessage) {
    try {
        def color = success ? "28a745" : "dc3545"  // Green for success, Red for failure
        def status = success ? "✓ Success" : "✗ Failed"
        def title = success ? "Pod Restart Completed" : "Pod Restart Failed"
        def jobUrl = env.BUILD_URL ?: "N/A"
        def buildNumber = env.BUILD_NUMBER ?: "N/A"
        def triggeredBy = env.BUILD_USER ?: "Jenkins"
        
        def facts = [
            [name: "Status", value: status],
            [name: "Environment", value: environment.toUpperCase()],
            [name: "Cluster", value: cluster],
            [name: "Namespace", value: namespace],
            [name: "Old Pod", value: oldPod],
        ]
        
        if (success) {
            facts.add([name: "New Pod", value: newPod])
        }
        
        facts.addAll([
            [name: "Duration", value: duration],
            [name: "Build Number", value: buildNumber],
            [name: "Triggered By", value: triggeredBy]
        ])
        
        if (!success && errorMessage) {
            facts.add([name: "Error", value: errorMessage])
        }
        
        def payload = [
            "@type": "MessageCard",
            "@context": "https://schema.org/extensions",
            "themeColor": color,
            "title": title,
            "summary": "${title}: ${oldPod}",
            "sections": [
                [
                    "activityTitle": "Kubernetes Pod Restart",
                    "activitySubtitle": "Environment: ${environment.toUpperCase()}",
                    "facts": facts
                ]
            ],
            "potentialAction": [
                [
                    "@type": "OpenUri",
                    "name": "View Build",
                    "targets": [
                        [
                            "os": "default",
                            "uri": jobUrl
                        ]
                    ]
                ]
            ]
        ]
        
        def payloadJson = groovy.json.JsonOutput.toJson(payload)
        
        sh """
            curl -X POST '${webhookUrl}' \
                -H 'Content-Type: application/json' \
                -d '${payloadJson.replace("'", "'\"'\"'")}'
        """
        
        echo "Teams notification sent successfully"
        
    } catch (Exception e) {
        echo "Warning: Failed to send Teams notification: ${e.getMessage()}"
        // Don't fail the build if notification fails
    }
}

// Helper function to get environment binding from config file
def getEnvironmentBinding(environment) {
    try {
        // KX Environment Configuration
        def KX_ENVIRONMENT_CONFIGURATION_FILE = "kx_environment_configuration.groovy"
        def KX_ENVIRONMENT_CONFIGURATION_JENKINS_LOAD_PATH = "${WORKSPACE}/.envvars/${KX_ENVIRONMENT_CONFIGURATION_FILE}"
        
        // Check if config file exists locally
        if (!fileExists(KX_ENVIRONMENT_CONFIGURATION_JENKINS_LOAD_PATH)) {
            // Try to pull from S3
            withCredentials([
                [$class: 'AmazonWebServicesCredentialsBinding', 
                 accessKeyVariable: 'AWS_ACCESS_KEY_ID', 
                 credentialsId: 'AWS_Credentials', 
                 secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']
            ]) {
                withAWS(region:'us-west-2', credentials:'AWS_Credentials') {
                    s3Download bucket: 'jenkins-kubernetes-deployments', 
                               file: KX_ENVIRONMENT_CONFIGURATION_JENKINS_LOAD_PATH, 
                               path: "release/${KX_ENVIRONMENT_CONFIGURATION_FILE}", 
                               force: true
                }
            }
        }
        
        if (fileExists(KX_ENVIRONMENT_CONFIGURATION_JENKINS_LOAD_PATH)) {
            load KX_ENVIRONMENT_CONFIGURATION_JENKINS_LOAD_PATH
            return env."${environment.toUpperCase()}"
        }
    } catch (Exception e) {
        echo "Warning: Could not load environment configuration: ${e.getMessage()}"
    }
    return null
}
