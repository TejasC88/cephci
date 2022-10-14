/*
    Post message to UMB for all RHCEPH releases that are passed FeatureComplete stage.

    A message is posted when a recipe file was modified in the last 7 days. The list of
    modified releases are then compared against a the list of feature complete releases.
    A message is posted for the filtered list.
*/
def featureCompleteReleases = ['RHCEPH-4.3.yaml', 'RHCEPH-5.3.yaml', 'RHCEPH-6.0.yaml']

def postUMB(def version, Map recipeMap) {
    /*
        This method posts a message to the UMB using the version information provided.

        Args:
            version (str):  Version information to be included in the payload.
            image (str):    Ceph image
    */
    def stableBuild = ( recipeMap.containsKey('osp') ) ? recipeMap['osp'] : [:]
    def payload = [
        "artifact": [
            "nvr": version,
            "latest": recipeMap['tier-0'],
            "stable": stableBuild,
            "scratch": "true"
        ],
        "category": "external",
        "contact": [
            "email": "cephci@redhat.com",
            "name": "Red Hat Ceph Storage QE Team"
        ],
        "issuer": "rhceph-qe-ci",
        "run": [
            "url": "${env.JENKINS_URL}/job/${env.JOB_NAME}/${env.BUILD_NUMBER}/",
            "log": "${env.JENKINS_URL}/job/${env.JOB_NAME}/${env.BUILD_NUMBER}/console"
        ],
        "version": "1.0.0"
    ]

    def msg = writeJSON returnText: true, json: payload
    def msgProps = """ PRODUCT = Red Hat Ceph Storage
        TOOL = cephci
    """
    sendCIMessage([
        providerName: 'Red Hat UMB',
        overrides:[topic: 'VirtualTopic.qe.ci.rhceph.product-scenario.test.queue'],
        messageContent: msg,
        messageProperties: msgProps,
        messageType: "Custom",
        failOnError: true
    ])
}

node('rhel-8-medium || ceph-qe-ci') {
    stage('prepareNode') {
        checkout(
            scm: [
                $class: 'GitSCM',
                branches: [[name: 'origin/master']],
                extensions: [[
                    $class: 'CleanBeforeCheckout',
                    deleteUntrackedNestedRepositories: true
                ], [
                    $class: 'WipeWorkspace'
                ], [
                    $class: 'CloneOption',
                    depth: 1,
                    noTags: true,
                    shallow: true,
                    timeout: 10,
                    reference: ''
                ]],
                userRemoteConfigs: [[
                    url: 'https://github.com/red-hat-storage/cephci.git'
                ]]
            ],
            changelog: false,
            poll: false
        )
        timeout(unit: "MINUTES", time: 10) {
            sh '''
                if [ ! -d /ceph/cephci-jenkins ]; then
                    sudo mkdir -p /ceph
                    sudo mount -t nfs -o sec=sys,nfsvers=4.1 reesi004.ceph.redhat.com:/ /ceph
                fi
            '''
        }
    }

    stage('postMessage') {
        def findRootDir = "/ceph/cephci-jenkins/latest-rhceph-container-info/"

        // Check if there was a new build in the last week for each supported release
        featureCompleteReleases.each {
            def out = sh(
                script: "find $findRootDir -name $it -mtime -7 -print",
                returnStdout: true
            )
            if (out?.trim()) {
                def recipeMap = readYaml file: out.trim()
                def nvr = it.replace(".yaml", "")

                println("Development build found for $nvr")

                postUMB(nvr, recipeMap)
            }
        }
    }
}
