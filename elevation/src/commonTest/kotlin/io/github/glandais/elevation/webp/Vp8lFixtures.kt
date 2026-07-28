package io.github.glandais.elevation.webp

import io.github.glandais.elevation.hexToBytes

/**
 * Real lossless WebP files, each chosen to make libwebp reach for a different transform, with
 * the exact RGBA they must decode to.
 *
 * Generated with Pillow (`save(..., "WEBP", lossless=True, exact=True)`), and their expected
 * pixels taken from Pillow's own decoder — so the assertions come from an implementation that
 * knows nothing about ours. The generator is deterministic; a fixture that changes from one run
 * to the next is not a fixture.
 *
 * Small on purpose: a 512 x 512 production tile would be 350 kB of hex in a source file nobody
 * can review, and what it adds over these is size rather than variety. The real tile is covered
 * by the INTEGRATION-gated comparison against the JVM decoder instead.
 */
object Vp8lFixtures {
    /** One file, its dimensions, and the RGBA it must produce. */
    class Case(
        val name: String,
        val width: Int,
        val height: Int,
        val webp: ByteArray,
        val expectedRgba: ByteArray,
    )

    /**
     * A smooth gradient. libwebp answers a gradient with the **predictor** transform, so
     * this is the fixture that fails first when a predictor is wrong.
     *
     * 16 x 12, 50 bytes of WebP.
     */
    val GRADIENT: Case =
        Case(
            name = "GRADIENT",
            width = 16,
            height = 12,
            webp =
                hexToBytes(
                    "524946462a000000574542505650384c1e0000002f0fc00200998ce87f6c22a2ff0121016184" +
                        "ff7735791e06212600b8ea2e",
                ),
            expectedRgba =
                hexToBytes(
                    "000000ff0f0008ff1e0010ff2d0018ff3c0020ff4b0028ff5a0030ff690038ff780040ff8700" +
                        "48ff960050ffa50058ffb40060ffc30068ffd20070ffe10078ff001408ff0f1410ff1e1418ff" +
                        "2d1420ff3c1428ff4b1430ff5a1438ff691440ff781448ff871450ff961458ffa51460ffb414" +
                        "68ffc31470ffd21478ffe11480ff002810ff0f2818ff1e2820ff2d2828ff3c2830ff4b2838ff" +
                        "5a2840ff692848ff782850ff872858ff962860ffa52868ffb42870ffc32878ffd22880ffe128" +
                        "88ff003c18ff0f3c20ff1e3c28ff2d3c30ff3c3c38ff4b3c40ff5a3c48ff693c50ff783c58ff" +
                        "873c60ff963c68ffa53c70ffb43c78ffc33c80ffd23c88ffe13c90ff005020ff0f5028ff1e50" +
                        "30ff2d5038ff3c5040ff4b5048ff5a5050ff695058ff785060ff875068ff965070ffa55078ff" +
                        "b45080ffc35088ffd25090ffe15098ff006428ff0f6430ff1e6438ff2d6440ff3c6448ff4b64" +
                        "50ff5a6458ff696460ff786468ff876470ff966478ffa56480ffb46488ffc36490ffd26498ff" +
                        "e164a0ff007830ff0f7838ff1e7840ff2d7848ff3c7850ff4b7858ff5a7860ff697868ff7878" +
                        "70ff877878ff967880ffa57888ffb47890ffc37898ffd278a0ffe178a8ff008c38ff0f8c40ff" +
                        "1e8c48ff2d8c50ff3c8c58ff4b8c60ff5a8c68ff698c70ff788c78ff878c80ff968c88ffa58c" +
                        "90ffb48c98ffc38ca0ffd28ca8ffe18cb0ff00a040ff0fa048ff1ea050ff2da058ff3ca060ff" +
                        "4ba068ff5aa070ff69a078ff78a080ff87a088ff96a090ffa5a098ffb4a0a0ffc3a0a8ffd2a0" +
                        "b0ffe1a0b8ff00b448ff0fb450ff1eb458ff2db460ff3cb468ff4bb470ff5ab478ff69b480ff" +
                        "78b488ff87b490ff96b498ffa5b4a0ffb4b4a8ffc3b4b0ffd2b4b8ffe1b4c0ff00c850ff0fc8" +
                        "58ff1ec860ff2dc868ff3cc870ff4bc878ff5ac880ff69c888ff78c890ff87c898ff96c8a0ff" +
                        "a5c8a8ffb4c8b0ffc3c8b8ffd2c8c0ffe1c8c8ff00dc58ff0fdc60ff1edc68ff2ddc70ff3cdc" +
                        "78ff4bdc80ff5adc88ff69dc90ff78dc98ff87dca0ff96dca8ffa5dcb0ffb4dcb8ffc3dcc0ff" +
                        "d2dcc8ffe1dcd0ff",
                ),
        )

    /**
     * Four flat colours: stored with the **colour-indexing** transform, 4 entries meaning
     * 2 bits per packed pixel — and the width is odd, so the last packed pixel of every row is
     * only half used.
     *
     * 13 x 5, 78 bytes of WebP.
     */
    val PALETTE: Case =
        Case(
            name = "PALETTE",
            width = 13,
            height = 5,
            webp =
                hexToBytes(
                    "5249464646000000574542505650384c390000002f0c0001001f2010204cf8f23f63434840e8" +
                        "fedf718d90803042f77fa5f90fe04f5382423692a07ba84662e45671a122fa1fea25f9b603ac" +
                        "7a00",
                ),
            expectedRgba =
                hexToBytes(
                    "0a141effc86432ff000000ffffffffff0a141effc86432ff000000ffffffffff0a141effc864" +
                        "32ff000000ffffffffff0a141effc86432ff000000ffffffffff0a141effc86432ff000000ff" +
                        "ffffffff0a141effc86432ff000000ffffffffff0a141effc86432ff000000ffffffffff0a14" +
                        "1effc86432ff000000ffffffffff0a141effc86432ff000000ffffffffff0a141effc86432ff" +
                        "000000ffffffffff0a141effc86432ff000000ffffffffff0a141effc86432ff000000ffffff" +
                        "ffff0a141effc86432ff000000ffffffffff0a141effc86432ff000000ffffffffff0a141eff" +
                        "c86432ff000000ffffffffff0a141effc86432ff000000ffffffffff0a141eff",
                ),
        )

    /**
     * Two colours — colour indexing at **1 bit per pixel**, the densest packing, with a
     * width that leaves the final packed pixel mostly empty.
     *
     * 11 x 3, 48 bytes of WebP.
     */
    val TWO_COLOURS: Case =
        Case(
            name = "TWO_COLOURS",
            width = 11,
            height = 3,
            webp =
                hexToBytes(
                    "5249464628000000574542505650384c1b0000002f0a8000000f10f3bffff31f0e14a46dc0ec" +
                        "fa7753ad11fd0f897300",
                ),
            expectedRgba =
                hexToBytes(
                    "0000ffff0000ffff0000ffff0000ffff0000ffff0000ffff0000ffff0000ffff0000ffff0000" +
                        "ffff0000ffff0000ffffff0000ffff0000ff0000ffffff0000ffff0000ff0000ffffff0000ff" +
                        "ff0000ff0000ffffff0000ff0000ffffff0000ffff0000ff0000ffffff0000ffff0000ff0000" +
                        "ffffff0000ffff0000ff0000ffffff0000ff",
                ),
        )

    /**
     * Correlated channels plus noise, which is what pushes libwebp into **subtract-green**
     * and the cross-colour transform — the two places a sign error hides.
     *
     * 24 x 9, 336 bytes of WebP.
     */
    val NOISY: Case =
        Case(
            name = "NOISY",
            width = 24,
            height = 9,
            webp =
                hexToBytes(
                    "5249464648010000574542505650384c3b0100002f17000200cd6444ff631311fd0f87d1b66d" +
                        "c60cf2fff3d500c300409a008a36fe3f3563e33092dcb641200150eebf5433131f718be03695" +
                        "926298eeebb091202d231bb248ef8f4fc676c14bc0742670234443b374bcfdee21943cb19069" +
                        "0854b2bc461f8205229039e1ab7f686004601f58b6e94d61d7578498337836016115a895ce9e" +
                        "0cfc1038292b3f6b113612559572e1004b373e3c2c8c411fe47a6108b5aa16f2773549e545db" +
                        "55586a96645478b5c1c9dd641b9c0d62c3057f050704bc6621c3407b4f277d86e14b0cfdade0" +
                        "d756c60af8824617202f9ce29d4a089b4df54623d34aa8fe741b7dd02cd0832f6a6a33874708" +
                        "da2e82009f4e0718a2065399de5440818227016e7c9b64b3147840c829d374796cd3c34fde90" +
                        "bbcfdd03fcc0700cb379a942d5c0138b03784dd582abb8ea8102152d64820000",
                ),
            expectedRgba =
                hexToBytes(
                    "060000ff0e0701ff190e0bff191512ff1e1c1aff26231fff362a22ff33312aff3d382fff443f" +
                        "34ff514641ff504d42ff58544aff5b5b4fff6d6261ff6f6960ff707068ff7e7773ff8a7e73ff" +
                        "8d8579ff988c87ff999390ffa19a91ffaaa197ff130b02ff19120aff1d1918ff292019ff2727" +
                        "27ff382e2eff413533ff433c38ff4f4341ff4e4a40ff585149ff605853ff5f5f53ff716660ff" +
                        "716d64ff7a746aff877b70ff868279ff8e8981ff909088ff9c978bffa29e9dffaba59bffb7ac" +
                        "a1ff1e160cff1f1d1dff26241dff342b2aff3a322bff3a3931ff48403dff50473eff554e45ff" +
                        "5e554dff605c55ff676359ff756a60ff7c7169ff7d7875ff847f7fff918684ff8e8d85ff9b94" +
                        "8aff9f9b99ffaba29dffb4a9a3ffb6b0aaffbbb7b7ff262121ff332821ff322f29ff3c362eff" +
                        "453d3aff4a443fff4e4b3fff5a524eff61594eff67605eff69675cff7a6e64ff7d7571ff7e7c" +
                        "77ff848379ff8d8a87ff939187ff9f988dffa29f97ffafa6a3ffb5adacffb4b4a8ffc3bbb8ff" +
                        "c7c2bfff2e2c28ff373329ff463a34ff474139ff4d4841ff514f48ff5b5654ff635d57ff6964" +
                        "64ff716b63ff737271ff7e7979ff8c807fff8e8787ff988e8eff99958cffa89c92ffa6a39eff" +
                        "adaaa0ffb4b1afffbcb8b2ffc7bfbdffc6c6bbffd0cdc1ff40372dff463e3aff4c453fff4c4c" +
                        "43ff565350ff625a53ff616160ff6a685cff776f6bff79766eff877d78ff888480ff8d8b86ff" +
                        "999289ffa09999ffa0a099ffa7a7a0ffb9aea6ffb6b5b1ffc8bcb0ffcdc3b9ffcccac1ffd7d1" +
                        "d1ffdcd8ceff4e4241ff4d4940ff5a5045ff62574fff605e54ff706561ff706c65ff787372ff" +
                        "857a78ff85817aff91887dff9a8f8dff9f968effa09d9cffa8a49effababa7ffb4b2a8ffc3b9" +
                        "afffcbc0b5ffd2c7c6ffd8cecbffd5d5d1ffe1dcd7ffe4e3e3ff594d44ff5e5451ff645b52ff" +
                        "64625bff6b6966ff73706fff7f776eff7e7e74ff8a8584ff8c8c85ff97938cffa59a94ffa9a1" +
                        "9dffb1a89fffb8afaeffbcb6adffc0bdb2ffcbc4c0ffcfcbc2ffddd2cfffe3d9cdffe1e0d4ff" +
                        "f3e7e3fff7eee4ff595856ff695f54ff706666ff776d64ff747471ff807b79ff8b827cff8b89" +
                        "86ff939090ff999796ffa59e95ffb1a59effaeaca4ffb6b3a7ffc1bab1ffc7c1baffd3c8c6ff" +
                        "d6cfc9ffdbd6cdffe2ddd8ffeae4e2fff3ebe2fffcf2effffff9f6ff",
                ),
        )

    /**
     * Actual Terrarium content: elevation in R and G, rising with x and y. The production
     * case, in miniature.
     *
     * 32 x 32, 124 bytes of WebP.
     */
    val TERRARIUM: Case =
        Case(
            name = "TERRARIUM",
            width = 32,
            height = 32,
            webp =
                hexToBytes(
                    "5249464674000000574542505650384c670000002f1fc007000980486a7fef1522fa9fba0008" +
                        "c2ffb88588fea7425124a9918e71807f3d91c30d49be028222e3fd8e50dbb60d036f29b653ae" +
                        "384b424133654c4083e082745f85cf47d1633d062a97f7c6d20d9b4997acfc4fa42893d2aaa7" +
                        "7a0e29caa474db530100",
                ),
            expectedRgba =
                hexToBytes(
                    "85dc00ff860400ff862c00ff865400ff867c00ff86a400ff86cc00ff86f400ff871c00ff8744" +
                        "00ff876c00ff879400ff87bc00ff87e400ff880c00ff883400ff885c00ff888400ff88ac00ff" +
                        "88d400ff88fc00ff892400ff894c00ff897400ff899c00ff89c400ff89ec00ff8a1400ff8a3c" +
                        "00ff8a6400ff8a8c00ff8ab400ff85f500ff861d00ff864500ff866d00ff869500ff86bd00ff" +
                        "86e500ff870d00ff873500ff875d00ff878500ff87ad00ff87d500ff87fd00ff882500ff884d" +
                        "00ff887500ff889d00ff88c500ff88ed00ff891500ff893d00ff896500ff898d00ff89b500ff" +
                        "89dd00ff8a0500ff8a2d00ff8a5500ff8a7d00ff8aa500ff8acd00ff860e00ff863600ff865e" +
                        "00ff868600ff86ae00ff86d600ff86fe00ff872600ff874e00ff877600ff879e00ff87c600ff" +
                        "87ee00ff881600ff883e00ff886600ff888e00ff88b600ff88de00ff890600ff892e00ff8956" +
                        "00ff897e00ff89a600ff89ce00ff89f600ff8a1e00ff8a4600ff8a6e00ff8a9600ff8abe00ff" +
                        "8ae600ff862700ff864f00ff867700ff869f00ff86c700ff86ef00ff871700ff873f00ff8767" +
                        "00ff878f00ff87b700ff87df00ff880700ff882f00ff885700ff887f00ff88a700ff88cf00ff" +
                        "88f700ff891f00ff894700ff896f00ff899700ff89bf00ff89e700ff8a0f00ff8a3700ff8a5f" +
                        "00ff8a8700ff8aaf00ff8ad700ff8aff00ff864000ff866800ff869000ff86b800ff86e000ff" +
                        "870800ff873000ff875800ff878000ff87a800ff87d000ff87f800ff882000ff884800ff8870" +
                        "00ff889800ff88c000ff88e800ff891000ff893800ff896000ff898800ff89b000ff89d800ff" +
                        "8a0000ff8a2800ff8a5000ff8a7800ff8aa000ff8ac800ff8af000ff8b1800ff865900ff8681" +
                        "00ff86a900ff86d100ff86f900ff872100ff874900ff877100ff879900ff87c100ff87e900ff" +
                        "881100ff883900ff886100ff888900ff88b100ff88d900ff890100ff892900ff895100ff8979" +
                        "00ff89a100ff89c900ff89f100ff8a1900ff8a4100ff8a6900ff8a9100ff8ab900ff8ae100ff" +
                        "8b0900ff8b3100ff867200ff869a00ff86c200ff86ea00ff871200ff873a00ff876200ff878a" +
                        "00ff87b200ff87da00ff880200ff882a00ff885200ff887a00ff88a200ff88ca00ff88f200ff" +
                        "891a00ff894200ff896a00ff899200ff89ba00ff89e200ff8a0a00ff8a3200ff8a5a00ff8a82" +
                        "00ff8aaa00ff8ad200ff8afa00ff8b2200ff8b4a00ff868b00ff86b300ff86db00ff870300ff" +
                        "872b00ff875300ff877b00ff87a300ff87cb00ff87f300ff881b00ff884300ff886b00ff8893" +
                        "00ff88bb00ff88e300ff890b00ff893300ff895b00ff898300ff89ab00ff89d300ff89fb00ff" +
                        "8a2300ff8a4b00ff8a7300ff8a9b00ff8ac300ff8aeb00ff8b1300ff8b3b00ff8b6300ff86a4" +
                        "00ff86cc00ff86f400ff871c00ff874400ff876c00ff879400ff87bc00ff87e400ff880c00ff" +
                        "883400ff885c00ff888400ff88ac00ff88d400ff88fc00ff892400ff894c00ff897400ff899c" +
                        "00ff89c400ff89ec00ff8a1400ff8a3c00ff8a6400ff8a8c00ff8ab400ff8adc00ff8b0400ff" +
                        "8b2c00ff8b5400ff8b7c00ff86bd00ff86e500ff870d00ff873500ff875d00ff878500ff87ad" +
                        "00ff87d500ff87fd00ff882500ff884d00ff887500ff889d00ff88c500ff88ed00ff891500ff" +
                        "893d00ff896500ff898d00ff89b500ff89dd00ff8a0500ff8a2d00ff8a5500ff8a7d00ff8aa5" +
                        "00ff8acd00ff8af500ff8b1d00ff8b4500ff8b6d00ff8b9500ff86d600ff86fe00ff872600ff" +
                        "874e00ff877600ff879e00ff87c600ff87ee00ff881600ff883e00ff886600ff888e00ff88b6" +
                        "00ff88de00ff890600ff892e00ff895600ff897e00ff89a600ff89ce00ff89f600ff8a1e00ff" +
                        "8a4600ff8a6e00ff8a9600ff8abe00ff8ae600ff8b0e00ff8b3600ff8b5e00ff8b8600ff8bae" +
                        "00ff86ef00ff871700ff873f00ff876700ff878f00ff87b700ff87df00ff880700ff882f00ff" +
                        "885700ff887f00ff88a700ff88cf00ff88f700ff891f00ff894700ff896f00ff899700ff89bf" +
                        "00ff89e700ff8a0f00ff8a3700ff8a5f00ff8a8700ff8aaf00ff8ad700ff8aff00ff8b2700ff" +
                        "8b4f00ff8b7700ff8b9f00ff8bc700ff870800ff873000ff875800ff878000ff87a800ff87d0" +
                        "00ff87f800ff882000ff884800ff887000ff889800ff88c000ff88e800ff891000ff893800ff" +
                        "896000ff898800ff89b000ff89d800ff8a0000ff8a2800ff8a5000ff8a7800ff8aa000ff8ac8" +
                        "00ff8af000ff8b1800ff8b4000ff8b6800ff8b9000ff8bb800ff8be000ff872100ff874900ff" +
                        "877100ff879900ff87c100ff87e900ff881100ff883900ff886100ff888900ff88b100ff88d9" +
                        "00ff890100ff892900ff895100ff897900ff89a100ff89c900ff89f100ff8a1900ff8a4100ff" +
                        "8a6900ff8a9100ff8ab900ff8ae100ff8b0900ff8b3100ff8b5900ff8b8100ff8ba900ff8bd1" +
                        "00ff8bf900ff873a00ff876200ff878a00ff87b200ff87da00ff880200ff882a00ff885200ff" +
                        "887a00ff88a200ff88ca00ff88f200ff891a00ff894200ff896a00ff899200ff89ba00ff89e2" +
                        "00ff8a0a00ff8a3200ff8a5a00ff8a8200ff8aaa00ff8ad200ff8afa00ff8b2200ff8b4a00ff" +
                        "8b7200ff8b9a00ff8bc200ff8bea00ff8c1200ff875300ff877b00ff87a300ff87cb00ff87f3" +
                        "00ff881b00ff884300ff886b00ff889300ff88bb00ff88e300ff890b00ff893300ff895b00ff" +
                        "898300ff89ab00ff89d300ff89fb00ff8a2300ff8a4b00ff8a7300ff8a9b00ff8ac300ff8aeb" +
                        "00ff8b1300ff8b3b00ff8b6300ff8b8b00ff8bb300ff8bdb00ff8c0300ff8c2b00ff876c00ff" +
                        "879400ff87bc00ff87e400ff880c00ff883400ff885c00ff888400ff88ac00ff88d400ff88fc" +
                        "00ff892400ff894c00ff897400ff899c00ff89c400ff89ec00ff8a1400ff8a3c00ff8a6400ff" +
                        "8a8c00ff8ab400ff8adc00ff8b0400ff8b2c00ff8b5400ff8b7c00ff8ba400ff8bcc00ff8bf4" +
                        "00ff8c1c00ff8c4400ff878500ff87ad00ff87d500ff87fd00ff882500ff884d00ff887500ff" +
                        "889d00ff88c500ff88ed00ff891500ff893d00ff896500ff898d00ff89b500ff89dd00ff8a05" +
                        "00ff8a2d00ff8a5500ff8a7d00ff8aa500ff8acd00ff8af500ff8b1d00ff8b4500ff8b6d00ff" +
                        "8b9500ff8bbd00ff8be500ff8c0d00ff8c3500ff8c5d00ff879e00ff87c600ff87ee00ff8816" +
                        "00ff883e00ff886600ff888e00ff88b600ff88de00ff890600ff892e00ff895600ff897e00ff" +
                        "89a600ff89ce00ff89f600ff8a1e00ff8a4600ff8a6e00ff8a9600ff8abe00ff8ae600ff8b0e" +
                        "00ff8b3600ff8b5e00ff8b8600ff8bae00ff8bd600ff8bfe00ff8c2600ff8c4e00ff8c7600ff" +
                        "87b700ff87df00ff880700ff882f00ff885700ff887f00ff88a700ff88cf00ff88f700ff891f" +
                        "00ff894700ff896f00ff899700ff89bf00ff89e700ff8a0f00ff8a3700ff8a5f00ff8a8700ff" +
                        "8aaf00ff8ad700ff8aff00ff8b2700ff8b4f00ff8b7700ff8b9f00ff8bc700ff8bef00ff8c17" +
                        "00ff8c3f00ff8c6700ff8c8f00ff87d000ff87f800ff882000ff884800ff887000ff889800ff" +
                        "88c000ff88e800ff891000ff893800ff896000ff898800ff89b000ff89d800ff8a0000ff8a28" +
                        "00ff8a5000ff8a7800ff8aa000ff8ac800ff8af000ff8b1800ff8b4000ff8b6800ff8b9000ff" +
                        "8bb800ff8be000ff8c0800ff8c3000ff8c5800ff8c8000ff8ca800ff87e900ff881100ff8839" +
                        "00ff886100ff888900ff88b100ff88d900ff890100ff892900ff895100ff897900ff89a100ff" +
                        "89c900ff89f100ff8a1900ff8a4100ff8a6900ff8a9100ff8ab900ff8ae100ff8b0900ff8b31" +
                        "00ff8b5900ff8b8100ff8ba900ff8bd100ff8bf900ff8c2100ff8c4900ff8c7100ff8c9900ff" +
                        "8cc100ff880200ff882a00ff885200ff887a00ff88a200ff88ca00ff88f200ff891a00ff8942" +
                        "00ff896a00ff899200ff89ba00ff89e200ff8a0a00ff8a3200ff8a5a00ff8a8200ff8aaa00ff" +
                        "8ad200ff8afa00ff8b2200ff8b4a00ff8b7200ff8b9a00ff8bc200ff8bea00ff8c1200ff8c3a" +
                        "00ff8c6200ff8c8a00ff8cb200ff8cda00ff881b00ff884300ff886b00ff889300ff88bb00ff" +
                        "88e300ff890b00ff893300ff895b00ff898300ff89ab00ff89d300ff89fb00ff8a2300ff8a4b" +
                        "00ff8a7300ff8a9b00ff8ac300ff8aeb00ff8b1300ff8b3b00ff8b6300ff8b8b00ff8bb300ff" +
                        "8bdb00ff8c0300ff8c2b00ff8c5300ff8c7b00ff8ca300ff8ccb00ff8cf300ff883400ff885c" +
                        "00ff888400ff88ac00ff88d400ff88fc00ff892400ff894c00ff897400ff899c00ff89c400ff" +
                        "89ec00ff8a1400ff8a3c00ff8a6400ff8a8c00ff8ab400ff8adc00ff8b0400ff8b2c00ff8b54" +
                        "00ff8b7c00ff8ba400ff8bcc00ff8bf400ff8c1c00ff8c4400ff8c6c00ff8c9400ff8cbc00ff" +
                        "8ce400ff8d0c00ff884d00ff887500ff889d00ff88c500ff88ed00ff891500ff893d00ff8965" +
                        "00ff898d00ff89b500ff89dd00ff8a0500ff8a2d00ff8a5500ff8a7d00ff8aa500ff8acd00ff" +
                        "8af500ff8b1d00ff8b4500ff8b6d00ff8b9500ff8bbd00ff8be500ff8c0d00ff8c3500ff8c5d" +
                        "00ff8c8500ff8cad00ff8cd500ff8cfd00ff8d2500ff886600ff888e00ff88b600ff88de00ff" +
                        "890600ff892e00ff895600ff897e00ff89a600ff89ce00ff89f600ff8a1e00ff8a4600ff8a6e" +
                        "00ff8a9600ff8abe00ff8ae600ff8b0e00ff8b3600ff8b5e00ff8b8600ff8bae00ff8bd600ff" +
                        "8bfe00ff8c2600ff8c4e00ff8c7600ff8c9e00ff8cc600ff8cee00ff8d1600ff8d3e00ff887f" +
                        "00ff88a700ff88cf00ff88f700ff891f00ff894700ff896f00ff899700ff89bf00ff89e700ff" +
                        "8a0f00ff8a3700ff8a5f00ff8a8700ff8aaf00ff8ad700ff8aff00ff8b2700ff8b4f00ff8b77" +
                        "00ff8b9f00ff8bc700ff8bef00ff8c1700ff8c3f00ff8c6700ff8c8f00ff8cb700ff8cdf00ff" +
                        "8d0700ff8d2f00ff8d5700ff889800ff88c000ff88e800ff891000ff893800ff896000ff8988" +
                        "00ff89b000ff89d800ff8a0000ff8a2800ff8a5000ff8a7800ff8aa000ff8ac800ff8af000ff" +
                        "8b1800ff8b4000ff8b6800ff8b9000ff8bb800ff8be000ff8c0800ff8c3000ff8c5800ff8c80" +
                        "00ff8ca800ff8cd000ff8cf800ff8d2000ff8d4800ff8d7000ff88b100ff88d900ff890100ff" +
                        "892900ff895100ff897900ff89a100ff89c900ff89f100ff8a1900ff8a4100ff8a6900ff8a91" +
                        "00ff8ab900ff8ae100ff8b0900ff8b3100ff8b5900ff8b8100ff8ba900ff8bd100ff8bf900ff" +
                        "8c2100ff8c4900ff8c7100ff8c9900ff8cc100ff8ce900ff8d1100ff8d3900ff8d6100ff8d89" +
                        "00ff88ca00ff88f200ff891a00ff894200ff896a00ff899200ff89ba00ff89e200ff8a0a00ff" +
                        "8a3200ff8a5a00ff8a8200ff8aaa00ff8ad200ff8afa00ff8b2200ff8b4a00ff8b7200ff8b9a" +
                        "00ff8bc200ff8bea00ff8c1200ff8c3a00ff8c6200ff8c8a00ff8cb200ff8cda00ff8d0200ff" +
                        "8d2a00ff8d5200ff8d7a00ff8da200ff88e300ff890b00ff893300ff895b00ff898300ff89ab" +
                        "00ff89d300ff89fb00ff8a2300ff8a4b00ff8a7300ff8a9b00ff8ac300ff8aeb00ff8b1300ff" +
                        "8b3b00ff8b6300ff8b8b00ff8bb300ff8bdb00ff8c0300ff8c2b00ff8c5300ff8c7b00ff8ca3" +
                        "00ff8ccb00ff8cf300ff8d1b00ff8d4300ff8d6b00ff8d9300ff8dbb00ff",
                ),
        )

    /** Every case, for tests that want to sweep them all. */
    val ALL: List<Case> = listOf(GRADIENT, PALETTE, TWO_COLOURS, NOISY, TERRARIUM)
}
