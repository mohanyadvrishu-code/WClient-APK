package org.cloudburstmc.protocol.bedrock.codec.v924.serializer;

import org.cloudburstmc.protocol.bedrock.codec.v712.serializer.ServerboundDiagnosticsSerializer_v712;

/**
 * Compatibility serializer for protocol 924.
 *
 * The diagnostics extension types are not included in this source snapshot,
 * so retain the protocol-712 payload instead of referencing unavailable
 * classes and making the whole codec uncompilable.
 */
public class ServerboundDiagnosticsSerializer_v924 extends ServerboundDiagnosticsSerializer_v712 {
    public static final ServerboundDiagnosticsSerializer_v924 INSTANCE =
            new ServerboundDiagnosticsSerializer_v924();
}
