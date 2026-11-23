# Third-Party Notices

Room Sound Monitor includes third-party machine-learning assets.

## 1. YAMNet TFLite Model

File:
- app/src/main/assets/yamnet.tflite

Description:
- YAMNet audio event classification model.
- Produces scores for 521 audio event categories.
- Originally developed by Google / TensorFlow authors.

Upstream project:
https://github.com/tensorflow/models/tree/master/research/audioset/yamnet

TensorFlow tutorial:
https://www.tensorflow.org/hub/tutorials/yamnet

SHA-256 of the included file:
10c95ea3eb9a7bb4cb8bddf6feb023250381008177ac162ce169694d05c317de

Note:
The exact origin and version of the binary file included in this
repository have not yet been independently verified.
The upstream links are provided for reference and do not establish
that this exact binary was downloaded from those locations.

## 2. AudioSet Class Map

File:
- app/src/main/assets/yamnet_class_map.csv

Description:
- Contains the class identifiers and display names used by YAMNet.
- Describes 521 audio event categories.

Upstream reference:
https://github.com/tensorflow/models/tree/master/research/audioset/yamnet

AudioSet:
https://research.google.com/audioset/

SHA-256 of the included file:
cdf24d193e196d9e95912a2667051ae203e92a2ba09449218ccb40ef787c6df2

Note:
The exact provenance and applicable license of the included copy
have not yet been independently verified.

## 3. Project Notice

Room Sound Monitor is an independent Android application.

It is not an official Google, TensorFlow, MediaPipe, or AudioSet product.

The application code and third-party assets may have different
authors and licensing terms. Their respective rights must be
respected.
