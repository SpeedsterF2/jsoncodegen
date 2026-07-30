scriptPos=${0%/*}

imageBase=schlothauer/jsoncodegen

if [ ! -z "$registry" ]; then
    echo "making use of registry definition!"
    imageBase = "$registry/$imageBase"
    echo "imageBase: $imageBase"
fi

imageTag=0.14.1

if [ -f $scriptPos/../../build.gradle ]; then
    imageTag=$(cat $scriptPos/../../build.gradle | grep project.version | awk '{ print $3 }' | sed -e "s-'--g")
fi

imageName="$imageBase:$imageTag"

echo "$imageName"
