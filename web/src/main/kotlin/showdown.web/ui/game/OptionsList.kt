package showdown.web.ui.game

import de.jensklingenberg.showdown.model.QuestionTask
import kotlinx.html.js.onClickFunction
import materialui.components.button.button
import materialui.components.button.enums.ButtonColor
import materialui.components.button.enums.ButtonSize
import materialui.components.button.enums.ButtonVariant
import react.RBuilder
import react.dom.h2
import react.dom.p
import showdown.web.wrapper.material.icons.TouchAppIcon

/**
 * Shows the list with options, that the use can vote
 */
fun RBuilder.optionsList(
    options: List<String>,
    selectedOptionIndex: Int,
    task: QuestionTask?,
    onOptionClicked: (Int) -> Unit
) {
    h2 {
        TouchAppIcon {}

        +"Select an answer:"
    }

    task?.let {
        val answer = it.answers

        answer.forEachIndexed { index, option ->

            p{
                button {
                    attrs {
                        this.size = ButtonSize.large
                        this.color = if (index != selectedOptionIndex) {
                            ButtonColor.primary
                        } else {
                            ButtonColor.secondary
                        }
                        variant = ButtonVariant.outlined
                        onClickFunction = {
                            onOptionClicked(index)
                        }

                    }
                    +option.text

                }
            }




        }
    }



}