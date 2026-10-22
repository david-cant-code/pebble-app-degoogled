Golden recordings are hex text, not binary, so the F-Droid source scanner
does not treat them as checked-in binaries. Add a line to index.txt plus
the two hex files. The PCM is the decoder output before removeDCBias.
No hardware capture is in this directory yet; synthetic-silence is generated
from the decoder example in the module README.
